package hd.protocol;

import java.util.List;
import java.util.Map;

/**
 * The envelope every command returns (printed as-is by the CLI).
 *
 * @param page paging info when {@code data} contains a page, else null
 * @param truncated notes about content left out of {@code data}
 * @param hints suggested next commands
 */
public record Response(boolean ok, String engine, Object data, PageInfo page, List<String> truncated, List<String> hints,
                       ErrorInfo error) {

    public record PageInfo(int limit, int offset, long total, String next) {}

    /** @param code stable error code, see {@link ErrorCode} */
    public record ErrorInfo(String code, String message, Map<String, Object> details) {}

    public static Response ok(String engine, Object data, PageInfo page, List<String> hints) {
        return new Response(true, engine, data, page, null, hints == null || hints.isEmpty() ? null : hints, null);
    }

    public static Response error(ErrorCode code, String message, Map<String, Object> details) {
        return new Response(false, null, null, null, null, null,
                new ErrorInfo(code.name(), message, details == null || details.isEmpty() ? null : details));
    }
}
