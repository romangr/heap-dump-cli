package hd.protocol;

/** Stable error codes. The skill and scripts react to these; the CLI maps them to exit codes. */
public enum ErrorCode {
    /** Usage problem: bad option, malformed address, unknown class, OQL syntax. */
    INVALID_ARGUMENT(2),
    /** The session is still indexing (details: state, progress, eta_s). */
    SESSION_NOT_READY(3),
    SESSION_NOT_FOUND(4),
    JOB_NOT_FOUND(4),
    OBJECT_NOT_FOUND(4),
    FILE_NOT_FOUND(4),
    /** The estimated memory for the dump exceeds the daemon's -Xmx. */
    INSUFFICIENT_MEMORY(5),
    /** Not enough free disk space for the dump's index files (details: required and free). */
    INSUFFICIENT_DISK(5),
    /** The analysis daemon ran out of memory (and exited). */
    ENGINE_OOM(5),
    /** The running daemon has a different -Xmx than requested. */
    DAEMON_XMX_MISMATCH(5),
    CAPABILITY_UNSUPPORTED(6),
    CORRUPT_DUMP(7),
    CANCELLED(8),
    DAEMON_UNAVAILABLE(9),
    INTERNAL(1);

    private final int exitCode;

    ErrorCode(int exitCode) {
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
