package hd.engine.api;

import java.nio.file.Path;

/**
 * A heap dump analysis engine. Implementations are discovered with {@link java.util.ServiceLoader}.
 * Everything above this interface (views, detectors, CLI) must work with any engine that passes the TCK.
 */
public interface HeapEngine {

    /** Short stable id, e.g. "mat". */
    String id();

    /** Engine version; written into index markers so stale indexes get rebuilt. */
    String version();

    /**
     * Opens (and if needed indexes) a heap dump. May take a long time for large dumps.
     *
     * @throws EngineException with codes CORRUPT_DUMP, ENGINE_OOM, CANCELLED, IO
     */
    Snapshot open(Path dump, OpenOptions options, Progress progress, Cancel cancel) throws EngineException;

    /** Whether up-to-date indexes for this dump exist, so {@link #open} will be fast and need less memory. */
    default boolean hasIndex(Path dump, OpenOptions options) {
        return false;
    }
}
