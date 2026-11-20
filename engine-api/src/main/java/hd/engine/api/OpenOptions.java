package hd.engine.api;

import java.nio.file.Path;

/**
 * @param indexDir where the engine keeps its index files; {@code null} = engine default (next to the dump)
 * @param keepUnreachable keep objects not reachable from GC roots instead of discarding them
 */
public record OpenOptions(Path indexDir, boolean keepUnreachable) {
    public static OpenOptions defaults() {
        return new OpenOptions(null, false);
    }
}
