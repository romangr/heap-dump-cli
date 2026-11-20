package hd.engine.mat;

import hd.engine.api.Cancel;
import hd.engine.api.Progress;
import org.eclipse.mat.util.IProgressListener;

/** Adapts MAT's progress listener protocol to {@link Progress} and {@link Cancel}. */
final class MatProgress implements IProgressListener {

    private final Progress progress;
    private final Cancel cancel;
    private volatile String phase = "working";
    private volatile long total = -1;
    private volatile long worked;

    MatProgress(Progress progress, Cancel cancel) {
        this.progress = progress == null ? Progress.NONE : progress;
        this.cancel = cancel == null ? Cancel.none() : cancel;
    }

    @Override
    public void beginTask(String name, int totalWork) {
        // MAT reports nested tasks (SimpleMonitor) as one outer task; keep the first meaningful total
        if (total <= 0 && totalWork > 0) {
            total = totalWork;
            worked = 0;
        }
        if (name != null && !name.isBlank()) {
            phase = name;
        }
        report();
    }

    @Override
    public void subTask(String name) {
        if (name != null && !name.isBlank()) {
            phase = name;
        }
        report();
    }

    @Override
    public void worked(int work) {
        worked += work;
        report();
    }

    @Override
    public void done() {
        if (total > 0) {
            worked = total;
        }
        report();
    }

    @Override
    public boolean isCanceled() {
        return cancel.isCancelled();
    }

    @Override
    public void setCanceled(boolean value) {
        if (value) {
            cancel.cancel();
        }
    }

    @Override
    public void sendUserMessage(Severity severity, String message, Throwable exception) {
        // MAT's informational messages are not surfaced; errors propagate as exceptions
    }

    private void report() {
        if (cancel.isCancelled()) {
            throw new OperationCanceledException();
        }
        double fraction = total > 0 ? Math.min(1.0, (double) worked / total) : -1;
        progress.update(phase, fraction);
    }
}
