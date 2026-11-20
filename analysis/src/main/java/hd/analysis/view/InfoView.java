package hd.analysis.view;

import hd.analysis.Heap;
import hd.analysis.Limits;
import hd.analysis.ObjRef;
import hd.analysis.Redaction;
import hd.analysis.Size;
import hd.engine.api.Cancel;
import hd.engine.api.Progress;
import hd.engine.api.SnapshotInfo;

import java.time.Instant;
import java.util.List;

/** Overview of a snapshot: sizes, counts and the biggest retainers / classes at a glance. */
public final class InfoView {

    public record Result(String engine, String dump, long objects, long classes, Size usedHeap, int identifierSize,
                         Boolean compressedOops, String jvm, String created, long threads,
                         List<ObjRef> topRetainers, List<HistogramView.Row> topClasses) {}

    private InfoView() {}

    public static Result summary(Heap heap, Redaction redaction, Cancel cancel) {
        SnapshotInfo i = heap.snapshot().info();
        var top = DominatorView.children(heap, hd.engine.api.Snapshot.ROOT, false, Limits.first(5), redaction, cancel);
        var classes = HistogramView.compute(heap, HistogramView.By.CLASS, null, HistogramView.Sort.SHALLOW,
                Limits.first(5), Progress.NONE, cancel);
        long threads = heap.snapshot().threads().count();
        return new Result(i.engine(), i.dump().toString(), i.objectCount(), i.classCount(), Size.of(i.usedHeap()),
                i.identifierSize(), i.compressedOops(), i.jvmInfo(),
                i.creationEpochMillis() > 0 ? Instant.ofEpochMilli(i.creationEpochMillis()).toString() : null,
                threads, top.rows().items(), classes.rows().items());
    }
}
