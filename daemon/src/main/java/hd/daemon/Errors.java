package hd.daemon;

import hd.engine.api.EngineException;
import hd.hprof.HprofFormatException;
import hd.protocol.ErrorCode;
import hd.protocol.Response;

import java.io.EOFException;
import java.io.IOException;

/** Maps exceptions to protocol errors. */
final class Errors {

    private Errors() {}

    static ErrorCode code(EngineException e) {
        return switch (e.code()) {
            case OBJECT_NOT_FOUND -> ErrorCode.OBJECT_NOT_FOUND;
            case CAPABILITY_UNSUPPORTED -> ErrorCode.CAPABILITY_UNSUPPORTED;
            case ENGINE_OOM -> ErrorCode.ENGINE_OOM;
            case CORRUPT_DUMP -> ErrorCode.CORRUPT_DUMP;
            case CANCELLED -> ErrorCode.CANCELLED;
            case INVALID_ARGUMENT -> ErrorCode.INVALID_ARGUMENT;
            case IO -> ErrorCode.FILE_NOT_FOUND;
            case INTERNAL -> ErrorCode.INTERNAL;
        };
    }

    static Response toResponse(Throwable t) {
        if (t instanceof HdException h) {
            return Response.error(h.code(), h.getMessage(), h.details());
        }
        if (t instanceof EngineException e) {
            return Response.error(code(e), e.getMessage(), null);
        }
        if (t instanceof HprofFormatException || t instanceof EOFException) {
            return Response.error(ErrorCode.CORRUPT_DUMP, t.getMessage(), null);
        }
        if (t instanceof IllegalArgumentException) {
            return Response.error(ErrorCode.INVALID_ARGUMENT, t.getMessage(), null);
        }
        if (t instanceof IOException) {
            return Response.error(ErrorCode.FILE_NOT_FOUND, t.getMessage(), null);
        }
        if (t instanceof OutOfMemoryError) {
            return Response.error(ErrorCode.ENGINE_OOM, "out of memory; increase the daemon -Xmx", null);
        }
        return Response.error(ErrorCode.INTERNAL, String.valueOf(t), null);
    }
}
