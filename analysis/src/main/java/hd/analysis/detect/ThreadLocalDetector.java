package hd.analysis.detect;

import hd.analysis.Heap;
import hd.analysis.JdkLayouts;
import hd.engine.api.Addresses;
import hd.engine.api.ObjectView;
import hd.engine.api.Snapshot;
import hd.engine.api.ThreadView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Large values held in threads' ThreadLocal maps — typical for pool threads that never call remove().
 * Groups by value class so "N pool threads each hold a cache" becomes one finding.
 */
public final class ThreadLocalDetector implements Detector {

    @Override
    public String id() {
        return "threadlocal";
    }

    @Override
    public String description() {
        return "large values kept in ThreadLocals (pool threads that never remove() them)";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    private static final class Group {
        long total;
        int values;
        int stale;
        final TreeSet<String> threads = new TreeSet<>();
        final List<long[]> samples = new ArrayList<>(); // [address, retained]
    }

    @Override
    public List<Finding> run(DetectContext ctx) {
        Heap heap = ctx.heap();
        Snapshot s = heap.snapshot();
        long min = ctx.threshold() / 4;
        Map<String, Group> byValueClass = new LinkedHashMap<>();
        for (ThreadView t : s.threads().toList()) {
            ctx.cancel().check();
            ObjectView thread = heap.object(t.address());
            String name = JdkLayouts.thread(heap, thread).name();
            for (String field : List.of("threadLocals", "inheritableThreadLocals")) {
                long map = thread.refField(field);
                if (map == Addresses.NULL) continue;
                long table = heap.object(map).refField("table");
                if (table == Addresses.NULL) continue;
                ObjectView entries = heap.object(table);
                for (long e : entries.readReferences(0, entries.length())) {
                    if (e == Addresses.NULL) continue;
                    ObjectView entry = heap.object(e);
                    long value = entry.refField("value");
                    if (value == Addresses.NULL) continue;
                    long retained = s.retainedSize(value);
                    if (retained < min) continue;
                    Group g = byValueClass.computeIfAbsent(heap.object(value).className(), k -> new Group());
                    g.total += retained;
                    g.values++;
                    if (entry.refField("referent") == Addresses.NULL) g.stale++;
                    g.threads.add(name == null ? Addresses.hex(t.address()) : name);
                    g.samples.add(new long[]{value, retained});
                }
            }
        }
        List<Finding> out = new ArrayList<>();
        for (var e : byValueClass.entrySet()) {
            Group g = e.getValue();
            if (g.total < ctx.threshold()) continue;
            g.samples.sort(Comparator.comparingLong((long[] a) -> a[1]).reversed());
            List<String> addrs = g.samples.stream().limit(5).map(a -> Addresses.hex(a[0])).toList();
            String threads = String.join(", ", g.threads.stream().limit(5).toList()) + (g.threads.size() > 5 ? ", …" : "");
            String explanation = String.format("%d ThreadLocal value(s) of %s in %d thread(s) (%s) retain %s in total."
                            + "%s Values in long-lived pool threads stay alive until ThreadLocal.remove() is called or the thread dies.",
                    g.values, e.getKey(), g.threads.size(), threads, heap.size(g.total).human(),
                    g.stale > 0 ? " " + g.stale + " entr" + (g.stale == 1 ? "y is" : "ies are")
                            + " stale (the ThreadLocal key was already collected, the value was not)." : "");
            out.add(new Finding(id(), Finding.bySize(g.total, heap.usedHeap()),
                    g.threads.size() + " thread(s) keep " + e.getKey() + " in ThreadLocals (" + heap.size(g.total).human() + ")",
                    explanation, new Finding.Evidence(addrs, g.values, heap.size(g.total)),
                    List.of("hd path-to-roots {session} " + addrs.get(0),
                            "hd threads {session} --thread \"" + g.threads.first() + "\"")));
        }
        return out;
    }
}
