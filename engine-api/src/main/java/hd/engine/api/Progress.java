package hd.engine.api;

/** Progress callback for long operations. Implementations must be thread-safe and cheap. */
@FunctionalInterface
public interface Progress {

    Progress NONE = (phase, fraction) -> {};

    /**
     * @param phase human-readable phase name, e.g. "parsing", "dominator tree"
     * @param fraction overall completion in [0, 1], or -1 if unknown
     */
    void update(String phase, double fraction);
}
