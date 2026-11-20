package hd.daemon;

import hd.protocol.ErrorCode;

import java.util.Map;

/** A failure with a protocol error code, raised by daemon logic. */
public class HdException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public HdException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public HdException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
