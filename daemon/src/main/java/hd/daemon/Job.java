package hd.daemon;

import hd.engine.api.Cancel;
import hd.protocol.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A unit of work whose result may outlive the request that started it (when it runs longer than the client waits).
 */
public final class Job {

    private final String id;
    private final String description;
    private final long startedNanos = System.nanoTime();
    private final Cancel cancel = new Cancel();
    private final CompletableFuture<Response> result = new CompletableFuture<>();
    private volatile String phase = "running";
    private volatile double progress = -1;
    private volatile long finishedMillis;

    Job(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return id;
    }

    Cancel cancel() {
        return cancel;
    }

    void progress(String phase, double fraction) {
        this.phase = phase;
        if (fraction >= 0) this.progress = fraction;
    }

    void complete(Response r) {
        finishedMillis = System.currentTimeMillis();
        result.complete(r);
    }

    boolean isDone() {
        return result.isDone();
    }

    long finishedMillis() {
        return finishedMillis;
    }

    /** @return the response, or null if still running after {@code timeoutMillis} */
    Response await(long timeoutMillis) {
        try {
            return timeoutMillis <= 0 ? result.getNow(null) : result.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause());
        }
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("job", id);
        m.put("description", description);
        m.put("state", isDone() ? "done" : "running");
        if (!isDone()) {
            m.put("phase", phase);
            if (progress >= 0) {
                m.put("progress", Math.round(progress * 1000) / 1000.0);
                double elapsed = (System.nanoTime() - startedNanos) / 1e9;
                if (progress > 0.02) m.put("eta_s", Math.round(elapsed / progress * (1 - progress)));
            }
            m.put("elapsed_s", Math.round((System.nanoTime() - startedNanos) / 1e9));
        }
        return m;
    }
}
