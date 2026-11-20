package hd.analysis.view;

import hd.analysis.Heap;
import hd.analysis.Limits;
import hd.analysis.ObjRef;
import hd.analysis.Page;
import hd.analysis.Redaction;
import hd.analysis.Size;
import hd.engine.api.Addresses;
import hd.engine.api.Cancel;
import hd.engine.api.ClassView;
import hd.engine.api.EngineException;
import hd.engine.api.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** Instances of a class (all class loaders' copies of that name), largest first. */
public final class InstancesView {

    public enum Sort { RETAINED, SHALLOW }

    /** @param classes the matching classes (one per defining loader) */
    public record Result(String className, List<String> classes, Page<ObjRef> instances, Size totalShallow) {}

    private InstancesView() {}

    public static Result list(Heap heap, String className, Sort sort, Limits limits, Redaction redaction, Cancel cancel) {
        Snapshot s = heap.snapshot();
        List<ClassView> classes = s.classesByName(className);
        if (classes.isEmpty()) {
            throw new EngineException(EngineException.Code.INVALID_ARGUMENT, "no class named " + className
                    + " (use the fully qualified name, arrays as e.g. byte[] or java.lang.Object[])");
        }
        long[] all = classes.stream().flatMapToLong(c -> s.instancesOf(c.address())).toArray();
        long[] key = new long[all.length];
        long shallow = 0;
        for (int i = 0; i < all.length; i++) {
            if ((i & 0xffff) == 0) cancel.check();
            long sh = s.shallowSize(all[i]);
            shallow += sh;
            key[i] = sort == Sort.RETAINED ? s.retainedSize(all[i]) : sh;
        }
        Integer[] order = IntStream.range(0, all.length).boxed().toArray(Integer[]::new);
        java.util.Arrays.sort(order, (a, b) -> Long.compare(key[b], key[a]));
        int from = Math.min(all.length, limits.offset());
        int to = Math.min(all.length, from + limits.limit());
        List<ObjRef> rows = new ArrayList<>();
        for (int i = from; i < to; i++) {
            rows.add(ObjRef.of(heap, all[order[i]], redaction));
        }
        return new Result(className, classes.stream().map(c -> Addresses.hex(c.address())).toList(),
                new Page<>(rows, all.length, limits.offset(), limits.limit()), heap.size(shallow));
    }
}
