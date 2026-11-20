package hd.analysis.detect;

import hd.analysis.Heap;
import hd.analysis.ObjRef;
import hd.analysis.Redaction;
import hd.engine.api.Addresses;
import hd.engine.api.Snapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level dominators that retain a large share of the heap. For each, descends to the "accumulation point" — the
 * deepest object that still holds most of the memory — and summarizes what it holds.
 */
public final class BigRetainersDetector implements Detector {

    static final double SHARE = 0.05;
    static final double DESCEND_SHARE = 0.80;

    @Override
    public String id() {
        return "big-retainers";
    }

    @Override
    public String description() {
        return "objects retaining >= 5% of the heap, with the point where the memory accumulates";
    }

    @Override
    public boolean defaultEnabled() {
        return true;
    }

    @Override
    public List<Finding> run(DetectContext ctx) {
        Heap heap = ctx.heap();
        Snapshot s = heap.snapshot();
        long min = Math.max(ctx.threshold(), (long) (heap.usedHeap() * SHARE));
        long[] top = s.dominated(Snapshot.ROOT).filter(a -> s.retainedSize(a) >= min).toArray();
        List<Finding> out = new ArrayList<>();
        for (long x : top) {
            ctx.cancel().check();
            long retained = s.retainedSize(x);
            ObjRef ref = ObjRef.of(heap, x, Redaction.ON);
            long acc = accumulationPoint(s, x);
            String what = describe(ref);
            StringBuilder explanation = new StringBuilder(what + " retains " + heap.size(retained).human()
                    + " (" + heap.size(retained).pct() + "% of the heap).");
            List<String> addrs = new ArrayList<>(List.of(ref.addr()));
            if (acc != x) {
                ObjRef accRef = ObjRef.of(heap, acc, Redaction.ON);
                explanation.append(" Most of it accumulates in ").append(describe(accRef)).append(" (")
                        .append(accRef.retained().human()).append(").");
                addrs.add(accRef.addr());
            }
            explanation.append(' ').append(contentSummary(heap, acc));
            out.add(new Finding(id(), Finding.bySize(retained, heap.usedHeap()),
                    what + " retains " + heap.size(retained).human(), explanation.toString().trim(),
                    new Finding.Evidence(addrs, 1, heap.size(retained)),
                    List.of("hd dominators {session} --root " + Addresses.hex(acc) + " --group-by class",
                            "hd path-to-roots {session} " + Addresses.hex(acc))));
        }
        return out;
    }

    /** Follows the dominator tree while a single child holds most of its parent's retained size. */
    static long accumulationPoint(Snapshot s, long start) {
        long cur = start;
        for (int depth = 0; depth < 100; depth++) {
            long retained = s.retainedSize(cur);
            long best = Addresses.NULL, bestSize = -1;
            for (long c : s.dominated(cur).toArray()) {
                long r = s.retainedSize(c);
                if (r > bestSize) {
                    best = c;
                    bestSize = r;
                }
            }
            if (bestSize < retained * DESCEND_SHARE) {
                return cur;
            }
            cur = best;
        }
        return cur;
    }

    /** "holds 200,000 java.lang.Integer (3.1 MiB) and 12 other classes". */
    static String contentSummary(Heap heap, long holder) {
        Snapshot s = heap.snapshot();
        Map<String, long[]> byClass = new HashMap<>();
        long n = 0;
        for (long c : s.dominated(holder).toArray()) {
            long[] g = byClass.computeIfAbsent(s.object(c).className(), k -> new long[2]);
            g[0]++;
            g[1] += s.retainedSize(c);
            n++;
        }
        if (n == 0) return "";
        var top = byClass.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed())
                .limit(3).toList();
        StringBuilder sb = new StringBuilder("It directly dominates ");
        for (int i = 0; i < top.size(); i++) {
            var e = top.get(i);
            if (i > 0) sb.append(i == top.size() - 1 ? " and " : ", ");
            sb.append(String.format("%,d %s (%s)", e.getValue()[0], e.getKey(), heap.size(e.getValue()[1]).human()));
        }
        if (byClass.size() > top.size()) sb.append(" among ").append(byClass.size()).append(" classes");
        return sb.append('.').toString();
    }

    static String describe(ObjRef r) {
        return r.label() != null ? r.cls() + " (" + r.label() + ")" : r.cls() + " @ " + r.addr();
    }
}
