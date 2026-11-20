package hd.analysis.view;

import hd.analysis.Heap;
import hd.analysis.Limits;
import hd.analysis.Page;
import hd.analysis.Size;
import hd.engine.api.Addresses;
import hd.engine.api.Cancel;
import hd.engine.api.ClassView;
import hd.engine.api.Progress;
import hd.engine.api.Snapshot;
import hd.engine.api.capability.FastHistogram;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Class histogram: instance count, shallow and (optionally) retained size per class, package or class loader. */
public final class HistogramView {

    public enum By { CLASS, PACKAGE, LOADER }

    public enum Sort { SHALLOW, COUNT, RETAINED }

    /**
     * @param classAddr class address for --by class, else null
     * @param retained minimum retained size of all instances (only when sorting by retained), else null
     */
    public record Row(String name, String classAddr, long count, Size shallow, Size retained) {}

    /** @param retainedNote explains how retained was computed, null if not computed */
    public record Result(String by, String sort, Page<Row> rows, Size totalShallow, long totalCount, String retainedNote) {}

    private HistogramView() {}

    public static Result compute(Heap heap, By by, Pattern filter, Sort sort, Limits limits, Progress progress, Cancel cancel) {
        Snapshot s = heap.snapshot();
        List<FastHistogram.Row> base = s.capability(FastHistogram.class)
                .map(h -> h.histogram(cancel))
                .orElseGet(() -> fallback(heap, cancel));

        Map<Long, Long> retainedByClass = sort == Sort.RETAINED ? retainedByClass(heap, base, progress, cancel) : null;

        Map<String, long[]> groups = new HashMap<>();   // key → [count, shallow, retained]
        Map<String, String> groupClassAddr = new HashMap<>();
        for (FastHistogram.Row r : base) {
            String key = switch (by) {
                case CLASS -> r.className() + "@" + Addresses.hex(r.classAddress());
                case PACKAGE -> packageOf(r.className());
                case LOADER -> loaderName(heap, r.loaderAddress());
            };
            String display = by == By.CLASS ? r.className() : key;
            if (filter != null && !filter.matcher(display).find()) continue;
            long[] g = groups.computeIfAbsent(key, k -> new long[3]);
            g[0] += r.count();
            g[1] += r.shallow();
            if (retainedByClass != null) g[2] += retainedByClass.getOrDefault(r.classAddress(), 0L);
            if (by == By.CLASS) groupClassAddr.put(key, Addresses.hex(r.classAddress()));
        }

        List<Row> rows = new ArrayList<>(groups.size());
        long totalShallow = 0, totalCount = 0;
        for (var e : groups.entrySet()) {
            long[] g = e.getValue();
            String name = by == By.CLASS ? e.getKey().substring(0, e.getKey().lastIndexOf('@')) : e.getKey();
            rows.add(new Row(name, groupClassAddr.get(e.getKey()), g[0], heap.size(g[1]),
                    retainedByClass == null ? null : heap.size(g[2])));
            totalShallow += g[1];
            totalCount += g[0];
        }
        Comparator<Row> cmp = switch (sort) {
            case SHALLOW -> Comparator.comparingLong((Row r) -> r.shallow().bytes());
            case COUNT -> Comparator.comparingLong(Row::count);
            case RETAINED -> Comparator.comparingLong((Row r) -> r.retained().bytes());
        };
        rows.sort(cmp.reversed().thenComparing(Row::name));
        String note = retainedByClass == null ? null
                : "retained = sum of retained sizes of the outermost instances of each class (a lower bound of what all "
                + "instances retain together; for packages/loaders: sum over their classes, which may overlap)";
        return new Result(by.name().toLowerCase(), sort.name().toLowerCase(), Page.of(rows, limits),
                heap.size(totalShallow), totalCount, note);
    }

    private static List<FastHistogram.Row> fallback(Heap heap, Cancel cancel) {
        Snapshot s = heap.snapshot();
        List<FastHistogram.Row> rows = new ArrayList<>();
        s.classes().forEach(c -> {
            cancel.check();
            if (c.instanceCount() == 0) return;
            long shallow = c.array() || c.instanceSize() < 0
                    ? s.instancesOf(c.address()).map(s::shallowSize).sum()
                    : c.instanceSize() * c.instanceCount();
            rows.add(new FastHistogram.Row(c.address(), c.name(), c.loaderAddress(), c.instanceCount(), shallow));
        });
        return rows;
    }

    /**
     * Retained size per class = sum of retained sizes of its outermost instances (those with no instance of the same
     * class above them in the dominator tree). One depth-first pass over the whole dominator tree.
     */
    private static Map<Long, Long> retainedByClass(Heap heap, List<FastHistogram.Row> base, Progress progress, Cancel cancel) {
        Snapshot s = heap.snapshot();
        long total = Math.max(1, s.info().objectCount());
        Map<Long, Long> result = new HashMap<>();
        Map<Long, Integer> onPath = new HashMap<>(); // class → instances of it on the current path
        java.util.ArrayDeque<long[]> children = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> next = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Long> pathClass = new java.util.ArrayDeque<>();
        children.push(s.dominated(Snapshot.ROOT).toArray());
        next.push(0);
        long visited = 0;
        while (!children.isEmpty()) {
            long[] kids = children.peek();
            int i = next.pop();
            if (i >= kids.length) {
                children.pop();
                if (!pathClass.isEmpty()) {
                    long c = pathClass.pop();
                    onPath.merge(c, -1, Integer::sum);
                }
                continue;
            }
            next.push(i + 1);
            long x = kids[i];
            long cls = s.classOf(x);
            if (onPath.getOrDefault(cls, 0) == 0) {
                result.merge(cls, s.retainedSize(x), Long::sum);
            }
            onPath.merge(cls, 1, Integer::sum);
            pathClass.push(cls);
            children.push(s.dominated(x).toArray());
            next.push(0);
            if ((++visited & 0xffff) == 0) {
                cancel.check();
                progress.update("retained by class", Math.min(1.0, (double) visited / total));
            }
        }
        return result;
    }

    static String packageOf(String className) {
        String n = className;
        int arr = n.indexOf('[');
        if (arr >= 0) n = n.substring(0, arr);
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "<default package>" : n.substring(0, dot);
    }

    static String loaderName(Heap heap, long loader) {
        if (loader == Addresses.NULL) return "<bootstrap>";
        try {
            ClassView cv = heap.classOf(loader);
            String name = hd.analysis.JdkLayouts.metadataString(heap, heap.object(loader).refField("name"));
            return cv.name() + (name == null ? "" : " \"" + name + "\"") + " @ " + Addresses.hex(loader);
        } catch (RuntimeException e) {
            return "loader @ " + Addresses.hex(loader);
        }
    }
}
