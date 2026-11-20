package hd.fixtures;

import hd.fixtures.model.LeakyValue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * One instance of each problem the v1 detectors look for.
 *
 * <ul>
 *   <li><b>threadlocal</b>: 4 pool threads ("leak-pool-N"), each with a 512 KiB {@link LeakyValue} in a ThreadLocal</li>
 *   <li><b>classloader</b>: 3 {@link LeakyLoader}s each loading its own copy of {@code PluginComponent}, kept alive</li>
 *   <li><b>collections</b>: a HashMap pre-sized to 1M buckets holding 3 entries; an ArrayList with 200 000 elements</li>
 *   <li><b>direct-buffers</b>: 8 direct ByteBuffers of 1 MiB held in a list</li>
 *   <li><b>dup-strings</b>: 5000 distinct String objects with the same content "duplicate-value-…"</li>
 *   <li><b>threads</b>: thread "local-holder" parked in {@link #holdLocal} with a 2 MiB byte[] in a local variable</li>
 * </ul>
 */
final class LeaksScenario implements FixtureMain.Scenario {

    static final int POOL_THREADS = 4;
    static final int LEAKY_VALUE_SIZE = 512 * 1024;
    static final int LOADERS = 3;
    static final int DIRECT_BUFFERS = 8;
    static final int DUP_STRINGS = 5000;
    static final int LOCAL_ARRAY_SIZE = 2 * 1024 * 1024;

    /** Anchors everything below as a static-field GC root. */
    static final List<Object> HOLDER = new ArrayList<>();

    private static final ThreadLocal<LeakyValue> LEAK = new ThreadLocal<>();

    private ExecutorService pool;
    private final CountDownLatch releaseLocalHolder = new CountDownLatch(1);
    private Thread localHolder;

    @Override
    public void build() throws Exception {
        buildThreadLocalLeak();
        buildClassLoaderLeak();
        buildCollections();
        buildDirectBuffers();
        buildDuplicateStrings();
        buildThreadWithBigLocal();
    }

    private void buildThreadLocalLeak() throws Exception {
        ThreadFactory factory = new ThreadFactory() {
            private int n;

            @Override
            public synchronized Thread newThread(Runnable r) {
                Thread t = new Thread(r, "leak-pool-" + n++);
                t.setDaemon(true);
                return t;
            }
        };
        pool = Executors.newFixedThreadPool(POOL_THREADS, factory);
        CountDownLatch done = new CountDownLatch(POOL_THREADS);
        CountDownLatch allStarted = new CountDownLatch(1);
        for (int i = 0; i < POOL_THREADS; i++) {
            pool.submit(() -> {
                LEAK.set(new LeakyValue(LEAKY_VALUE_SIZE));
                done.countDown();
                // keep all tasks running until every thread has its own value, so each thread gets exactly one
                allStarted.await();
                return null;
            });
        }
        done.await();
        allStarted.countDown();
    }

    private void buildClassLoaderLeak() throws Exception {
        URL codeSource = LeaksScenario.class.getProtectionDomain().getCodeSource().getLocation();
        for (int i = 0; i < LOADERS; i++) {
            LeakyLoader loader = new LeakyLoader("plugin-deploy-" + i, new URL[]{codeSource});
            Class<?> c = loader.loadClass("hd.fixtures.model.PluginComponent");
            HOLDER.add(c.getField("STATE").get(null)); // forces class init
            HOLDER.add(c);
        }
    }

    private void buildCollections() {
        Map<String, String> sparse = new HashMap<>(1 << 20);
        sparse.put("a", "1");
        sparse.put("b", "2");
        sparse.put("c", "3");
        sparse.size();
        HOLDER.add(sparse);

        List<Integer> big = new ArrayList<>();
        for (int i = 0; i < 200_000; i++) {
            big.add(i);
        }
        HOLDER.add(big);
    }

    private void buildDirectBuffers() {
        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < DIRECT_BUFFERS; i++) {
            buffers.add(ByteBuffer.allocateDirect(1 << 20));
        }
        HOLDER.add(buffers);
    }

    private void buildDuplicateStrings() {
        List<String> strings = new ArrayList<>(DUP_STRINGS);
        char[] content = "duplicate-value-of-some-length".toCharArray();
        for (int i = 0; i < DUP_STRINGS; i++) {
            // new String(char[]) gives every copy its own backing array, like deserialized or parsed strings
            strings.add(new String(content));
        }
        HOLDER.add(strings);
    }

    private void buildThreadWithBigLocal() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        localHolder = new Thread(() -> holdLocal(parked), "local-holder");
        localHolder.setDaemon(true);
        localHolder.start();
        parked.await();
        // give the thread time to actually block inside await()
        Thread.sleep(200);
    }

    private void holdLocal(CountDownLatch parked) {
        byte[] bigLocal = new byte[LOCAL_ARRAY_SIZE];
        parked.countDown();
        try {
            releaseLocalHolder.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // use the local after the wait so it stays live in the frame
        if (bigLocal.length == 42) {
            System.out.println("unreachable");
        }
    }

    @Override
    public void release() throws Exception {
        releaseLocalHolder.countDown();
        localHolder.join(5000);
        pool.shutdownNow();
        pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    /** A distinctively named loader type so the dump shows several loaders of the same kind. */
    static final class LeakyLoader extends URLClassLoader {
        LeakyLoader(String name, URL[] urls) {
            super(name, urls, ClassLoader.getPlatformClassLoader());
        }
    }
}
