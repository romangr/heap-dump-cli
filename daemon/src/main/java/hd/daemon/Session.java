package hd.daemon;

import hd.analysis.Heap;
import hd.engine.api.Cancel;
import hd.engine.api.Snapshot;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** One opened (or opening) heap dump. */
public final class Session {

    public enum State { INDEXING, READY, FAILED, CLOSED }

    private final String id;
    private final Path dump;
    private final long startedNanos = System.nanoTime();
    private final Cancel cancel = new Cancel();
    private volatile State state = State.INDEXING;
    private volatile String phase = "starting";
    private volatile double progress;
    private volatile Snapshot snapshot;
    private volatile Heap heap;
    private volatile String error;
    private volatile String errorCode;
    private volatile long lastUsed = System.currentTimeMillis();
    private volatile int busy;

    Session(String id, Path dump) {
        this.id = id;
        this.dump = dump;
    }

    public String id() {
        return id;
    }

    public Path dump() {
        return dump;
    }

    public State state() {
        return state;
    }

    public Heap heap() {
        return heap;
    }

    Cancel cancel() {
        return cancel;
    }

    void progress(String phase, double fraction) {
        this.phase = phase;
        if (fraction >= 0) this.progress = fraction;
    }

    void ready(Snapshot s) {
        this.snapshot = s;
        this.heap = new Heap(s);
        this.progress = 1.0;
        this.phase = "ready";
        this.state = State.READY;
    }

    void failed(String code, String message) {
        this.errorCode = code;
        this.error = message;
        this.state = State.FAILED;
    }

    synchronized void close() {
        cancel.cancel();
        if (snapshot != null) {
            snapshot.close();
            snapshot = null;
            heap = null;
        }
        state = State.CLOSED;
    }

    void touch() {
        lastUsed = System.currentTimeMillis();
    }

    long lastUsed() {
        return lastUsed;
    }

    synchronized void enter() {
        busy++;
        touch();
    }

    synchronized void exit() {
        busy--;
        touch();
    }

    synchronized boolean isBusy() {
        return busy > 0;
    }

    /** Estimated seconds until indexing completes, from progress so far; null if unknown. */
    Long etaSeconds() {
        double p = progress;
        if (state != State.INDEXING || p <= 0.02) return null;
        double elapsed = (System.nanoTime() - startedNanos) / 1e9;
        return Math.round(elapsed / p * (1 - p));
    }

    /** JSON-friendly state description. */
    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("session", id);
        m.put("dump", dump.toString());
        m.put("state", state.name().toLowerCase());
        if (state == State.INDEXING) {
            m.put("phase", phase);
            m.put("progress", Math.round(progress * 1000) / 1000.0);
            Long eta = etaSeconds();
            if (eta != null) m.put("eta_s", eta);
        }
        if (state == State.FAILED) {
            m.put("error_code", errorCode);
            m.put("error", error);
        }
        if (state == State.READY && snapshot != null) {
            var info = snapshot.info();
            m.put("objects", info.objectCount());
            m.put("used_heap", hd.analysis.Size.of(info.usedHeap()));
        }
        return m;
    }
}
