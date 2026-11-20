package hd.analysis.view;

import hd.analysis.Heap;
import hd.analysis.Limits;
import hd.analysis.ObjRef;
import hd.analysis.Page;
import hd.analysis.Redaction;
import hd.analysis.Size;
import hd.engine.api.Addresses;
import hd.engine.api.Cancel;
import hd.engine.api.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/** Children of an object in the dominator tree (or the top level), largest retained first. */
public final class DominatorView {

    /**
     * @param parent the parent object, or null for the top level
     * @param rows individual objects (when not grouped)
     * @param groups per-class groups (when grouped)
     * @param childrenTotal number of dominated objects
     */
    public record Result(ObjRef parent, Page<ObjRef> rows, Page<Group> groups, long childrenTotal, Size childrenRetained) {}

    /** Children of one class: how many, how much they retain together, and the largest one. */
    public record Group(String cls, long count, Size retained, Size shallow, String largestAddr) {}

    private DominatorView() {}

    public static Result children(Heap heap, long parent, boolean groupByClass, Limits limits, Redaction redaction, Cancel cancel) {
        Snapshot s = heap.snapshot();
        long[] kids = s.dominated(parent).toArray();
        long[] retained = new long[kids.length];
        long sum = 0;
        for (int i = 0; i < kids.length; i++) {
            if ((i & 0xffff) == 0) cancel.check();
            retained[i] = s.retainedSize(kids[i]);
            sum += retained[i];
        }
        ObjRef parentRef = parent == Snapshot.ROOT ? null : ObjRef.of(heap, parent, redaction);

        if (groupByClass) {
            Map<Long, long[]> byClass = new HashMap<>(); // class → [count, retained, shallow, largestAddr, largestRetained]
            for (int i = 0; i < kids.length; i++) {
                if ((i & 0xffff) == 0) cancel.check();
                long cls = s.object(kids[i]).classAddress();
                long[] g = byClass.computeIfAbsent(cls, k -> new long[]{0, 0, 0, 0, -1});
                g[0]++;
                g[1] += retained[i];
                g[2] += s.shallowSize(kids[i]);
                if (retained[i] > g[4]) {
                    g[3] = kids[i];
                    g[4] = retained[i];
                }
            }
            List<Group> groups = new ArrayList<>();
            for (var e : byClass.entrySet()) {
                long[] g = e.getValue();
                groups.add(new Group(heap.classView(e.getKey()).name(), g[0], heap.size(g[1]), heap.size(g[2]), Addresses.hex(g[3])));
            }
            groups.sort(Comparator.comparingLong((Group g) -> g.retained().bytes()).reversed());
            return new Result(parentRef, null, Page.of(groups, limits), kids.length, heap.size(sum));
        }

        Integer[] order = IntStream.range(0, kids.length).boxed().toArray(Integer[]::new);
        java.util.Arrays.sort(order, (a, b) -> Long.compare(retained[b], retained[a]));
        int from = Math.min(kids.length, limits.offset());
        int to = Math.min(kids.length, from + limits.limit());
        List<ObjRef> rows = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            rows.add(ObjRef.of(heap, kids[order[i]], redaction));
        }
        return new Result(parentRef, new Page<>(rows, kids.length, limits.offset(), limits.limit()), null, kids.length, heap.size(sum));
    }
}
