package hd.analysis.view;

import hd.analysis.Heap;
import hd.analysis.Limits;
import hd.analysis.ObjRef;
import hd.analysis.Page;
import hd.analysis.Redaction;
import hd.engine.api.Cancel;
import hd.engine.api.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** Incoming or outgoing references of an object, largest retained first. */
public final class RefsView {

    public enum Direction { IN, OUT }

    public record Result(ObjRef obj, String direction, Page<ObjRef> refs) {}

    private RefsView() {}

    public static Result refs(Heap heap, long address, Direction dir, Limits limits, Redaction redaction, Cancel cancel) {
        Snapshot s = heap.snapshot();
        long[] refs = (dir == Direction.IN ? s.inbound(address) : s.outbound(address)).distinct().toArray();
        long[] retained = new long[refs.length];
        for (int i = 0; i < refs.length; i++) {
            if ((i & 0xffff) == 0) cancel.check();
            retained[i] = s.retainedSize(refs[i]);
        }
        Integer[] order = IntStream.range(0, refs.length).boxed().toArray(Integer[]::new);
        java.util.Arrays.sort(order, (a, b) -> Long.compare(retained[b], retained[a]));
        int from = Math.min(refs.length, limits.offset());
        int to = Math.min(refs.length, from + limits.limit());
        List<ObjRef> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            rows.add(ObjRef.of(heap, refs[order[i]], redaction));
        }
        return new Result(ObjRef.of(heap, address, redaction), dir.name().toLowerCase(),
                new Page<>(rows, refs.length, limits.offset(), limits.limit()));
    }
}
