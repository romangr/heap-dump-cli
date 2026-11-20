package hd.analysis.detect;

import hd.analysis.Heap;
import hd.engine.api.Cancel;
import hd.engine.api.Progress;

/** What a detector gets: the heap, cancellation and progress. */
public record DetectContext(Heap heap, Cancel cancel, Progress progress) {

    /** Default "is this worth reporting" threshold: 1% of the heap, at most 1 MiB, at least 64 KiB. */
    public long threshold() {
        return Math.max(64 * 1024, Math.min(1024 * 1024, heap.usedHeap() / 100));
    }
}
