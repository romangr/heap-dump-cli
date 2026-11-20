package hd.engine.api;

/** Cooperative cancellation token shared between the caller and a long-running operation. */
public final class Cancel {

    private volatile boolean cancelled;

    public static Cancel none() {
        return new Cancel();
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** @throws EngineException with code CANCELLED if cancellation was requested */
    public void check() {
        if (cancelled) {
            throw new EngineException(EngineException.Code.CANCELLED, "operation cancelled");
        }
    }
}
