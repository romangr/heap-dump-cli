package hd.analysis.detect;

import hd.analysis.Heap;
import hd.analysis.JdkLayouts;
import hd.analysis.Size;
import hd.engine.api.Addresses;
import hd.engine.api.ClassView;
import hd.engine.api.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Off-heap memory held through java.nio.DirectByteBuffer objects. The buffers are small on the heap but pin native
 * memory (counted against -XX:MaxDirectMemorySize), so heap-based views miss them.
 */
public final class DirectBuffersDetector implements Detector {

    @Override
    public String id() {
        return "direct-buffers";
    }

    @Override
    public String description() {
        return "native memory pinned by DirectByteBuffers, grouped by holder";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public List<Finding> run(DetectContext ctx) {
        Heap heap = ctx.heap();
        Snapshot s = heap.snapshot();
        List<ClassView> classes = s.classes()
                .filter(c -> !c.array() && heap.isSubclassOf(c.address(), "java.nio.DirectByteBuffer")).toList();
        long totalCapacity = 0;
        long buffers = 0;
        long mapped = 0;
        long mappedCount = 0;
        Map<String, long[]> byHolder = new LinkedHashMap<>(); // holder class → [count, capacity, sample]
        List<long[]> largest = new ArrayList<>();
        for (ClassView c : classes) {
            for (long b : s.instancesOf(c.address()).toArray()) {
                ctx.cancel().check();
                var view = heap.object(b);
                if (JdkLayouts.isMappedFile(view) && view.refField("att") == Addresses.NULL) {
                    mapped += JdkLayouts.intOf(view.field("capacity")).orElse(0);
                    mappedCount++;
                    continue;
                }
                long cap = JdkLayouts.directBufferOwnedCapacity(view);
                if (cap <= 0) continue;
                totalCapacity += cap;
                buffers++;
                String holder = holder(heap, b);
                long[] g = byHolder.computeIfAbsent(holder, k -> new long[]{0, 0, b});
                g[0]++;
                g[1] += cap;
                largest.add(new long[]{b, cap});
            }
        }
        if (totalCapacity < ctx.threshold()) return List.of();

        largest.sort(Comparator.comparingLong((long[] x) -> x[1]).reversed());
        var holders = byHolder.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed())
                .limit(3).toList();
        StringBuilder sb = new StringBuilder();
        for (var h : holders) {
            if (!sb.isEmpty()) sb.append("; ");
            sb.append(String.format("%,d held by %s (%s)", h.getValue()[0], h.getKey(), Size.human(h.getValue()[1])));
        }
        Size size = Size.of(totalCapacity);
        // native memory is in addition to the heap; rank it against the heap size anyway
        Finding.Severity sev = Finding.bySize(totalCapacity, heap.usedHeap());
        return List.of(new Finding(id(), sev,
                String.format("%,d direct buffers pin %s of native memory", buffers, size.human()),
                String.format("DirectByteBuffers own %s of off-heap memory (not part of the %s heap). By holder: %s. "
                                + "This memory is freed only when the buffer objects are collected.%s",
                        size.human(), Size.human(heap.usedHeap()), sb,
                        mappedCount == 0 ? "" : String.format(" Not counted: %,d memory-mapped file buffer(s) of %s.",
                                mappedCount, Size.human(mapped))),
                new Finding.Evidence(largest.stream().limit(5).map(x -> Addresses.hex(x[0])).toList(), buffers, size),
                List.of("hd path-to-roots {session} " + Addresses.hex(largest.get(0)[0]),
                        "hd instances {session} java.nio.DirectByteBuffer --sort retained")));
    }

    /**
     * Class of the first referrer that is not a java.lang.ref.Reference (direct buffers are also registered with a
     * Cleaner, so their dominator is usually just the GC root). Arrays are skipped to name the collection holding them.
     */
    static String holder(Heap heap, long buffer) {
        Snapshot s = heap.snapshot();
        long cur = buffer;
        for (int depth = 0; depth < 3; depth++) {
            long next = Addresses.NULL;
            for (long r : s.inbound(cur).toArray()) {
                var o = heap.object(r);
                if (o.kind() == hd.engine.api.ObjectKind.CLASS) return "static field of " + heap.classView(r).name();
                if (heap.isSubclassOf(o.classAddress(), "java.lang.ref.Reference")) continue;
                if (o.kind() != hd.engine.api.ObjectKind.OBJECT_ARRAY) return o.className();
                next = r;
                break;
            }
            if (next == Addresses.NULL) return depth == 0 ? "<GC root / cleaner only>" : heap.object(cur).className();
            cur = next;
        }
        return heap.object(cur).className();
    }
}
