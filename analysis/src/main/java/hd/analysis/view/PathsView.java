package hd.analysis.view;

import hd.analysis.Heap;
import hd.analysis.ObjRef;
import hd.analysis.Redaction;
import hd.engine.api.Addresses;
import hd.engine.api.Cancel;
import hd.engine.api.EngineException;
import hd.engine.api.FieldValue;
import hd.engine.api.GcRoot;
import hd.engine.api.ObjectKind;
import hd.engine.api.ObjectView;
import hd.engine.api.Snapshot;
import hd.engine.api.capability.FastGcRootPaths;
import hd.engine.api.capability.FastGcRootPaths.RefKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shortest paths from an object to GC roots ("who keeps this alive"). Paths with the same shape (classes and fields)
 * are grouped so repeated structures are reported once with a count.
 */
public final class PathsView {

    /**
     * @param via how this object references the previous step's object: field name, "[index]", or null for the
     *            first step
     * @param rootTypes GC root types if this step is a root, else empty
     */
    public record Step(ObjRef obj, String via, List<String> rootTypes) {}

    public record PathGroup(int count, List<Step> path) {}

    /** @param engineNative whether the engine computed the paths (else our breadth-first fallback) */
    public record Result(ObjRef obj, List<String> excluded, boolean isRoot, List<PathGroup> paths, boolean engineNative) {}

    private static final int MAX_ARRAY_SCAN = 1 << 20;
    private static final int FALLBACK_MAX_VISITED = 2_000_000;

    private PathsView() {}

    public static Result toRoots(Heap heap, long address, Set<RefKind> exclude, int maxPaths, Redaction redaction, Cancel cancel) {
        Snapshot s = heap.snapshot();
        ObjRef self = ObjRef.of(heap, address, redaction);
        var cap = s.capability(FastGcRootPaths.class);
        List<long[]> raw = cap.isPresent()
                ? cap.get().paths(address, exclude, maxPaths, cancel)
                : bfs(heap, address, exclude, maxPaths, cancel);

        Map<String, PathGroup> groups = new LinkedHashMap<>();
        for (long[] p : raw) {
            List<Step> steps = new ArrayList<>(p.length);
            StringBuilder shape = new StringBuilder();
            for (int i = 0; i < p.length; i++) {
                String via = i == 0 ? null : via(heap, p[i], p[i - 1]);
                List<String> roots = i == p.length - 1 ? s.rootTypes(p[i]).stream().map(GcRoot.Type::name).toList() : List.of();
                Step step = new Step(ObjRef.of(heap, p[i], redaction), via, roots);
                steps.add(step);
                shape.append(step.obj().cls()).append('|').append(via == null ? "" : via.startsWith("[") ? "[]" : via).append('/');
            }
            groups.compute(shape.toString(), (k, g) -> g == null ? new PathGroup(1, steps) : new PathGroup(g.count() + 1, g.path()));
        }
        List<String> excluded = exclude.stream().map(k -> k.name().toLowerCase()).sorted().toList();
        return new Result(self, excluded, !s.rootTypes(address).isEmpty(), List.copyOf(groups.values()), cap.isPresent());
    }

    /** How {@code holder} references {@code target}: a field name or an array index. */
    static String via(Heap heap, long holder, long target) {
        ObjectView h = heap.object(holder);
        if (h.kind() == ObjectKind.OBJECT_ARRAY) {
            int len = Math.min(h.length(), MAX_ARRAY_SCAN);
            for (int off = 0; off < len; off += 8192) {
                long[] chunk = h.readReferences(off, Math.min(8192, len - off));
                for (int i = 0; i < chunk.length; i++) {
                    if (chunk[i] == target) return "[" + (off + i) + "]";
                }
            }
            return "[?]";
        }
        if (h.kind() == ObjectKind.INSTANCE || h.kind() == ObjectKind.CLASS) {
            for (FieldValue f : h.fields()) {
                if (f.isReference() && f.address() == target) {
                    return h.kind() == ObjectKind.CLASS ? "static " + f.name() : f.name();
                }
            }
            if (h.classAddress() == target) return "<class>";
            if (h.kind() == ObjectKind.CLASS) {
                var cv = heap.classView(holder);
                if (cv.loaderAddress() == target) return "<classloader>";
                if (cv.superclassAddress() == target) return "<super>";
            }
        }
        return null;
    }

    private static final Map<RefKind, String> REF_CLASSES = Map.of(
            RefKind.WEAK, "java.lang.ref.WeakReference",
            RefKind.SOFT, "java.lang.ref.SoftReference",
            RefKind.PHANTOM, "java.lang.ref.PhantomReference",
            RefKind.FINAL, "java.lang.ref.Finalizer");

    /** Engine-independent fallback: breadth-first search over inbound references. */
    public static List<long[]> bfs(Heap heap, long start, Set<RefKind> exclude, int maxPaths, Cancel cancel) {
        Snapshot s = heap.snapshot();
        Map<Long, Long> parent = new HashMap<>(); // referrer → the object it references (one step back towards start)
        ArrayDeque<Long> queue = new ArrayDeque<>();
        List<long[]> paths = new ArrayList<>();
        parent.put(start, Addresses.NULL);
        queue.add(start);
        while (!queue.isEmpty() && paths.size() < maxPaths) {
            if (parent.size() > FALLBACK_MAX_VISITED) {
                throw new EngineException(EngineException.Code.INTERNAL, "path search exceeded " + FALLBACK_MAX_VISITED + " objects");
            }
            long cur = queue.poll();
            if ((parent.size() & 0xfff) == 0) cancel.check();
            if (!s.rootTypes(cur).isEmpty()) {
                List<Long> path = new ArrayList<>();
                for (long x = cur; x != Addresses.NULL; x = parent.get(x)) path.add(x);
                // collected root → … → start; reverse to start → … → root
                long[] arr = new long[path.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = path.get(arr.length - 1 - i);
                paths.add(arr);
                continue;
            }
            for (long ref : s.inbound(cur).toArray()) {
                if (parent.containsKey(ref) || excludedEdge(heap, ref, cur, exclude)) continue;
                parent.put(ref, cur);
                queue.add(ref);
            }
        }
        return paths;
    }

    private static boolean excludedEdge(Heap heap, long holder, long target, Set<RefKind> exclude) {
        if (exclude.isEmpty()) return false;
        ObjectView h = heap.object(holder);
        if (h.kind() != ObjectKind.INSTANCE || h.refField("referent") != target) return false;
        for (RefKind k : exclude) {
            if (heap.isSubclassOf(h.classAddress(), REF_CLASSES.get(k))) return true;
        }
        return false;
    }
}
