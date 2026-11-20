package hd.analysis;

/** Paging window. Defaults keep agent-facing output small. */
public record Limits(int offset, int limit) {

    public static final int DEFAULT_LIMIT = 25;

    public Limits {
        if (offset < 0) throw new IllegalArgumentException("offset must be >= 0");
        if (limit < 1) throw new IllegalArgumentException("limit must be >= 1");
    }

    public static Limits first(int limit) {
        return new Limits(0, limit);
    }

    public static Limits defaults() {
        return new Limits(0, DEFAULT_LIMIT);
    }
}
