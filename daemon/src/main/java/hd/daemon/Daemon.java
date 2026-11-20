package hd.daemon;

import hd.protocol.HdPaths;
import hd.protocol.Json;
import hd.protocol.Request;
import hd.protocol.Response;
import hd.protocol.Transport;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The analysis daemon: one per user (per HD_HOME), serving requests on a Unix socket until idle for too long.
 */
public final class Daemon {

    private final DaemonConfig config;
    private final HdPaths paths;
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "hd-worker");
        t.setDaemon(true);
        return t;
    });
    private final SessionManager sessions;
    private final Jobs jobs;
    private final Commands commands;
    private final Instant started = Instant.now();
    private volatile long lastActivity = System.currentTimeMillis();
    private volatile boolean stopping;
    private ServerSocketChannel server;
    private FileChannel lockChannel;

    public Daemon(DaemonConfig config) {
        this.config = config;
        this.paths = config.paths();
        this.sessions = new SessionManager(config, workers);
        this.jobs = new Jobs(workers);
        this.commands = new Commands(sessions, jobs, this::status, this::requestShutdown);
    }

    /** @return false if another daemon already owns this home */
    public boolean start() throws IOException {
        Files.createDirectories(paths.home());
        restrictToOwner(paths.home());
        lockChannel = FileChannel.open(paths.home().resolve("daemon.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = lockChannel.tryLock();
        if (lock == null) {
            lockChannel.close();
            return false;
        }
        Files.deleteIfExists(paths.socket()); // stale socket from a crashed daemon (we hold the lock)
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(paths.socket()));
        Files.writeString(paths.daemonInfo(), Json.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(info()));

        Thread acceptor = new Thread(this::acceptLoop, "hd-accept");
        acceptor.start();
        ScheduledExecutorService idle = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hd-idle");
            t.setDaemon(true);
            return t;
        });
        idle.scheduleAtFixedRate(this::checkIdle, 10, 10, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup, "hd-shutdown"));
        return true;
    }

    private static void restrictToOwner(Path dir) {
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // non-POSIX file system
        }
    }

    private void acceptLoop() {
        try (ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor()) {
            while (!stopping) {
                SocketChannel ch;
                try {
                    ch = server.accept();
                } catch (IOException e) {
                    if (stopping) break;
                    continue;
                }
                handlers.submit(() -> serve(ch));
            }
        }
    }

    private void serve(SocketChannel ch) {
        try (ch) {
            byte[] line = Transport.readLine(ch);
            if (line == null) return;
            lastActivity = System.currentTimeMillis();
            Response response;
            try {
                Request request = Json.MAPPER.readValue(line, Request.class);
                response = commands.handle(request);
            } catch (IOException e) {
                response = Response.error(hd.protocol.ErrorCode.INVALID_ARGUMENT, "malformed request: " + e.getMessage(), null);
            }
            Transport.writeLine(ch, Json.MAPPER.writeValueAsBytes(response));
            lastActivity = System.currentTimeMillis();
        } catch (IOException e) {
            // client went away
        }
    }

    private void checkIdle() {
        if (sessions.anyIndexing() || jobs.anyRunning()) {
            lastActivity = System.currentTimeMillis();
            return;
        }
        if (System.currentTimeMillis() - lastActivity > config.idleTimeout().toMillis()) {
            requestShutdown();
        }
    }

    void requestShutdown() {
        if (stopping) return;
        stopping = true;
        // reply first, then exit (the shutdown hook cleans up)
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            System.exit(0);
        }, "hd-exit");
        t.start();
    }

    private void cleanup() {
        stopping = true;
        jobs.cancelAll();
        sessions.closeAll();
        try {
            if (server != null) server.close();
            Files.deleteIfExists(paths.socket());
            Files.deleteIfExists(paths.daemonInfo());
            if (lockChannel != null) lockChannel.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    Map<String, Object> info() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pid", ProcessHandle.current().pid());
        m.put("started", started.toString());
        long max = Runtime.getRuntime().maxMemory();
        m.put("xmx", System.getProperty("hd.xmx", hd.analysis.Size.human(max)));
        m.put("xmx_bytes", max);
        m.put("xmx_source", System.getProperty("hd.xmx.source", "unknown"));
        m.put("jvm_opts", ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("-X") || a.startsWith("-XX")).toList());
        m.put("engine", sessions.engine().id() + " " + sessions.engine().version());
        m.put("idle_timeout_min", config.idleTimeout().toMinutes());
        m.put("socket", paths.socket().toString());
        return m;
    }

    Map<String, Object> status() {
        Map<String, Object> m = info();
        Runtime rt = Runtime.getRuntime();
        m.put("heap_used", hd.analysis.Size.of(rt.totalMemory() - rt.freeMemory()));
        m.put("uptime_s", (System.currentTimeMillis() - started.toEpochMilli()) / 1000);
        m.put("sessions", sessions.list().stream().map(Session::describe).toList());
        List<Map<String, Object>> js = jobs.describeAll();
        if (!js.isEmpty()) m.put("jobs", js);
        return m;
    }
}
