package hd.analysis.detect;

import hd.analysis.Heap;
import hd.analysis.JdkLayouts;
import hd.analysis.ObjRef;
import hd.analysis.Redaction;
import hd.engine.api.Addresses;
import hd.engine.api.ClassView;
import hd.engine.api.ObjectView;
import hd.engine.api.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Problem collections: very large ones (unbounded caches, growing lists) and sparse ones (huge backing arrays with
 * few elements, e.g. a map pre-sized to 1M entries holding 3).
 */
public final class CollectionsDetector implements Detector {

    static final List<String> BASES = List.of("java.util.HashMap", "java.util.WeakHashMap", "java.util.Hashtable",
            "java.util.concurrent.ConcurrentHashMap", "java.util.IdentityHashMap", "java.util.ArrayList", "java.util.Vector",
            "java.util.ArrayDeque", "java.util.LinkedList", "java.util.TreeMap", "java.util.HashSet",
            "java.util.concurrent.LinkedBlockingQueue", "java.util.concurrent.ArrayBlockingQueue");

    static final double LARGE_SHARE = 0.01;
    static final long LARGE_MIN = 1024 * 1024;
    /** A "large" collection must also have many elements; few elements retaining a lot is big-retainers' job. */
    static final long LARGE_MIN_ELEMENTS = 1000;
    static final int SPARSE_MIN_CAPACITY = 256;
    static final double SPARSE_FILL = 0.10;

    @Override
    public String id() {
        return "collections";
    }

    @Override
    public String description() {
        return "very large collections (>= 1% of heap, >= 1000 elements) and sparse ones (< 10% filled backing arrays)";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    private static final class Sparse {
        long count;
        long wasted;
        final List<long[]> samples = new ArrayList<>(); // [addr, wasted]
    }

    @Override
    public List<Finding> run(DetectContext ctx) {
        Heap heap = ctx.heap();
        Snapshot s = heap.snapshot();
        long largeMin = Math.max(LARGE_MIN, (long) (heap.usedHeap() * LARGE_SHARE));

        List<ClassView> classes = s.classes().filter(c -> !c.array() && c.instanceCount() > 0
                && BASES.stream().anyMatch(b -> heap.isSubclassOf(c.address(), b))).toList();
        long total = classes.stream().mapToLong(ClassView::instanceCount).sum();
        long done = 0;

        List<long[]> large = new ArrayList<>(); // [addr, retained]
        Map<String, Sparse> sparse = new LinkedHashMap<>();
        for (ClassView c : classes) {
            for (long a : s.instancesOf(c.address()).toArray()) {
                if ((++done & 0xfff) == 0) {
                    ctx.cancel().check();
                    ctx.progress().update("collections", (double) done / Math.max(1, total));
                }
                long retained = s.retainedSize(a);
                if (retained < 1024) continue; // tiny: cannot be large or meaningfully sparse
                ObjectView o = heap.object(a);
                var shape = JdkLayouts.collection(heap, o);
                if (shape.isEmpty()) continue;
                var sh = shape.get();
                if (retained >= largeMin && sh.size() >= LARGE_MIN_ELEMENTS) large.add(new long[]{a, retained});
                if (sh.backingArray() == Addresses.NULL) continue;
                if (sh.capacity() >= SPARSE_MIN_CAPACITY && sh.fillRatio() < SPARSE_FILL) {
                    long arrayBytes = s.shallowSize(sh.backingArray());
                    long wasted = (long) (arrayBytes * (1 - sh.fillRatio()));
                    Sparse g = sparse.computeIfAbsent(c.name(), k -> new Sparse());
                    g.count++;
                    g.wasted += wasted;
                    g.samples.add(new long[]{a, wasted});
                }
            }
        }

        List<Finding> out = new ArrayList<>();
        // large: report outermost only (skip collections inside an already reported one)
        large.sort(Comparator.comparingLong((long[] x) -> x[1]).reversed());
        Set<Long> reported = new HashSet<>();
        for (long[] l : large) {
            if (insideReported(s, l[0], reported)) continue;
            reported.add(l[0]);
            ObjRef r = ObjRef.of(heap, l[0], Redaction.ON);
            out.add(new Finding(id(), Finding.bySize(l[1], heap.usedHeap()),
                    "Large " + r.cls() + " (" + (r.label() == null ? "" : r.label() + ", ") + r.retained().human() + ")",
                    String.format("%s @ %s retains %s (%s%% of the heap)%s. Check whether it is an unbounded cache, "
                                    + "a list that is never cleared, or legitimately sized.",
                            r.cls(), r.addr(), r.retained().human(), r.retained().pct(),
                            r.label() == null ? "" : " with " + r.label()),
                    new Finding.Evidence(List.of(r.addr()), 1, r.retained()),
                    List.of("hd path-to-roots {session} " + r.addr(),
                            "hd dominators {session} --root " + r.addr() + " --group-by class")));
            if (reported.size() >= 10) break;
        }
        for (var e : sparse.entrySet()) {
            Sparse g = e.getValue();
            if (g.wasted < ctx.threshold()) continue;
            g.samples.sort(Comparator.comparingLong((long[] x) -> x[1]).reversed());
            List<String> addrs = g.samples.stream().limit(5).map(x -> Addresses.hex(x[0])).toList();
            ObjRef first = ObjRef.of(heap, g.samples.get(0)[0], Redaction.ON);
            out.add(new Finding(id(), Finding.bySize(g.wasted, heap.usedHeap()),
                    g.count + " sparse " + e.getKey() + " waste " + heap.size(g.wasted).human(),
                    String.format("%d %s instance(s) use less than %d%% of their backing array, wasting about %s. "
                                    + "The largest has %s. Typical causes: over-sized initial capacity, or a map/list "
                                    + "that grew and was emptied but never shrunk.",
                            g.count, e.getKey(), (int) (SPARSE_FILL * 100), heap.size(g.wasted).human(), first.label()),
                    new Finding.Evidence(addrs, g.count, heap.size(g.wasted)),
                    List.of("hd object {session} " + addrs.get(0), "hd path-to-roots {session} " + addrs.get(0))));
        }
        return out;
    }

    private static boolean insideReported(Snapshot s, long a, Set<Long> reported) {
        long d = s.immediateDominator(a);
        for (int i = 0; i < 200 && d != Snapshot.ROOT; i++) {
            if (reported.contains(d)) return true;
            d = s.immediateDominator(d);
        }
        return false;
    }
}
