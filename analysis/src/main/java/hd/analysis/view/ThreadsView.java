package hd.analysis.view;

import hd.analysis.Heap;
import hd.analysis.JdkLayouts;
import hd.analysis.Limits;
import hd.analysis.ObjRef;
import hd.analysis.Page;
import hd.analysis.Redaction;
import hd.analysis.Size;
import hd.engine.api.Addresses;
import hd.engine.api.Cancel;
import hd.engine.api.Snapshot;
import hd.engine.api.ThreadView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Threads from the heap dump, sorted by how much their stack locals retain. Threads with identical stacks are
 * summarized in {@code stackGroups}.
 */
public final class ThreadsView {

    /** @param locals objects referenced from this frame's locals (only with --with-locals) */
    public record Frame(String method, String location, List<ObjRef> locals) {}

    /**
     * @param localsRetained retained size of the objects referenced from the stack locals (excluding the thread itself)
     * @param threadRetained retained size of the Thread object itself (includes its ThreadLocals)
     */
    public record Row(ObjRef thread, String name, String state, Boolean daemon, Size localsRetained, Size threadRetained,
                      int frameCount, List<Frame> frames) {}

    /** @param threads names of up to 5 threads with this stack */
    public record StackGroup(int count, List<String> threads, List<String> topFrames) {}

    public record Result(boolean stackDataAvailable, Page<Row> threads, List<StackGroup> stackGroups, String note) {}

    private static final int DEFAULT_FRAMES = 12;
    private static final int GROUP_FRAMES = 6;

    private ThreadsView() {}

    /**
     * @param threadFilter name substring or address ("0x…") of a single thread to show in full, or null for all
     */
    public static Result list(Heap heap, String threadFilter, boolean withLocals, Limits limits, Redaction redaction, Cancel cancel) {
        Snapshot s = heap.snapshot();
        List<ThreadView> threads = s.threads().toList();
        boolean anyFrames = threads.stream().anyMatch(t -> !t.frames().isEmpty());

        List<Row> rows = new ArrayList<>();
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Map<String, List<String>> groupFrames = new LinkedHashMap<>();
        for (ThreadView t : threads) {
            cancel.check();
            var info = JdkLayouts.thread(heap, heap.object(t.address()));
            String name = info.name() == null ? Addresses.hex(t.address()) : info.name();
            if (threadFilter != null && !matches(threadFilter, name, t.address())) continue;

            long localsRetained = localsRetained(s, t);

            boolean full = threadFilter != null;
            int frameLimit = full ? Integer.MAX_VALUE : DEFAULT_FRAMES;
            List<Frame> frames = new ArrayList<>();
            for (ThreadView.Frame f : t.frames()) {
                if (frames.size() >= frameLimit) break;
                List<ObjRef> localRefs = null;
                if (withLocals) {
                    localRefs = new ArrayList<>();
                    for (long l : f.locals()) localRefs.add(ObjRef.of(heap, l, redaction));
                    localRefs.sort(Comparator.comparingLong((ObjRef r) -> r.retained().bytes()).reversed());
                }
                frames.add(new Frame(f.method(), f.location(), localRefs));
            }
            rows.add(new Row(ObjRef.shallow(heap, t.address(), redaction), name, info.state(), info.daemon(),
                    heap.size(localsRetained), heap.size(s.retainedSize(t.address())), t.frames().size(), frames));

            List<String> top = t.frames().stream().limit(GROUP_FRAMES).map(ThreadView.Frame::method).toList();
            String key = String.join("\n", t.frames().stream().map(ThreadView.Frame::method).toList());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(name);
            groupFrames.putIfAbsent(key, top);
        }
        rows.sort(Comparator.comparingLong((Row r) -> r.localsRetained().bytes())
                .thenComparingLong(r -> r.threadRetained().bytes()).reversed());

        List<StackGroup> stackGroups = new ArrayList<>();
        for (var e : groups.entrySet()) {
            if (e.getValue().size() >= 2 && !e.getKey().isEmpty()) {
                stackGroups.add(new StackGroup(e.getValue().size(), e.getValue().stream().limit(5).toList(), groupFrames.get(e.getKey())));
            }
        }
        stackGroups.sort(Comparator.comparingInt(StackGroup::count).reversed());

        String note = anyFrames ? null : "the dump contains no thread stack data; frames and locals are unavailable";
        if (threadFilter != null && rows.isEmpty()) {
            note = "no thread matches '" + threadFilter + "'";
        }
        return new Result(anyFrames, Page.of(rows, limits), stackGroups, note);
    }

    /**
     * Retained size of the objects referenced from the thread's stack, excluding the Thread object itself (it is
     * "this" in Thread.run). Engines may place locals below the thread or below each other in the dominator tree,
     * so a local is only counted when no other counted local dominates it.
     */
    static long localsRetained(Snapshot s, ThreadView t) {
        java.util.Set<Long> locals = new java.util.HashSet<>();
        t.frames().forEach(f -> Arrays.stream(f.locals()).forEach(locals::add));
        locals.remove(t.address());
        long sum = 0;
        for (long x : locals) {
            boolean nested = false;
            long d = s.immediateDominator(x);
            for (int i = 0; i < 1000 && d != Snapshot.ROOT; i++) {
                if (locals.contains(d)) {
                    nested = true;
                    break;
                }
                d = s.immediateDominator(d);
            }
            if (!nested) sum += s.retainedSize(x);
        }
        return sum;
    }

    private static boolean matches(String filter, String name, long address) {
        if (filter.startsWith("0x")) {
            return Addresses.parse(filter) == address;
        }
        return name.contains(filter);
    }
}
