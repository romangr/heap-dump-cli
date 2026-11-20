package hd.engine.api;

/** Typed engine failure. The code is stable and is mapped to CLI error codes. */
public class EngineException extends RuntimeException {

    public enum Code {
        /** No object at the given address. */
        OBJECT_NOT_FOUND,
        /** The engine does not provide the requested capability. */
        CAPABILITY_UNSUPPORTED,
        /** The engine ran out of memory. */
        ENGINE_OOM,
        /** The dump file is truncated, malformed or of an unknown format. */
        CORRUPT_DUMP,
        /** The operation was cancelled through {@link Cancel}. */
        CANCELLED,
        /** Invalid argument, e.g. a malformed OQL query. */
        INVALID_ARGUMENT,
        /** I/O failure. */
        IO,
        /** Anything else. */
        INTERNAL
    }

    private final Code code;

    public EngineException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public EngineException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public static EngineException notFound(long address) {
        return new EngineException(Code.OBJECT_NOT_FOUND, "no object at " + Addresses.hex(address));
    }

    public static EngineException unsupported(Class<?> capability) {
        return new EngineException(Code.CAPABILITY_UNSUPPORTED, capability.getSimpleName() + " is not supported by this engine");
    }
}
