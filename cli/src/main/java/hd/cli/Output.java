package hd.cli;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import hd.protocol.ErrorCode;
import hd.protocol.Json;
import hd.protocol.Response;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Prints responses (JSON by default, simple tables on request) and maps them to exit codes. */
final class Output {

    enum Format { JSON, TABLE }

    private Output() {}

    static int print(Response r, Format format, boolean compact, PrintStream out) {
        try {
            if (format == Format.TABLE) {
                table(r, out);
            } else {
                var writer = compact ? Json.MAPPER.writer() : Json.MAPPER.writerWithDefaultPrettyPrinter();
                out.println(writer.writeValueAsString(r));
            }
        } catch (JsonProcessingException e) {
            out.println("{\"ok\":false,\"error\":{\"code\":\"INTERNAL\",\"message\":\"cannot render output\"}}");
            return 1;
        }
        return exitCode(r);
    }

    static int exitCode(Response r) {
        if (r.ok()) return 0;
        try {
            return ErrorCode.valueOf(r.error().code()).exitCode();
        } catch (IllegalArgumentException e) {
            return 1;
        }
    }

    // ---- table rendering (for humans; agents use JSON)

    private static void table(Response r, PrintStream out) {
        if (!r.ok()) {
            out.println("error " + r.error().code() + ": " + r.error().message());
            if (r.error().details() != null) {
                r.error().details().forEach((k, v) -> out.println("  " + k + ": " + v));
            }
            hints(r, out);
            return;
        }
        JsonNode data = Json.MAPPER.valueToTree(r.data());
        render(data, out, "");
        if (r.page() != null) {
            out.printf("(%d-%d of %d)%n", r.page().offset() + 1,
                    Math.min(r.page().total(), (long) r.page().offset() + r.page().limit()), r.page().total());
            if (r.page().next() != null) out.println("next: " + r.page().next());
        }
        hints(r, out);
    }

    private static void hints(Response r, PrintStream out) {
        if (r.hints() != null && !r.hints().isEmpty()) {
            out.println();
            r.hints().forEach(h -> out.println("> " + h));
        }
    }

    private static void render(JsonNode node, PrintStream out, String indent) {
        if (node.isArray()) {
            renderArray(node, out, indent);
            return;
        }
        if (!node.isObject()) {
            out.println(indent + scalar(node));
            return;
        }
        for (Map.Entry<String, JsonNode> e : node.properties()) {
            JsonNode v = e.getValue();
            if (isSize(v) || isObjRef(v) || v.isValueNode() || (v.isArray() && allScalar(v))) {
                out.println(indent + e.getKey() + ": " + scalar(v));
            } else if (v.isObject() && v.has("items")) {
                out.println(indent + e.getKey() + ":");
                renderArray(v.get("items"), out, indent + "  ");
            } else {
                out.println(indent + e.getKey() + ":");
                render(v, out, indent + "  ");
            }
        }
    }

    private static void renderArray(JsonNode arr, PrintStream out, String indent) {
        if (arr.isEmpty()) {
            out.println(indent + "(none)");
            return;
        }
        boolean flat = true;
        Set<String> cols = new LinkedHashSet<>();
        for (JsonNode row : arr) {
            if (!row.isObject()) {
                flat = false;
                break;
            }
            row.fieldNames().forEachRemaining(cols::add);
            for (JsonNode v : row) {
                if (!(v.isValueNode() || isSize(v) || isObjRef(v) || (v.isArray() && v.size() <= 3 && allScalar(v)))) flat = false;
            }
        }
        if (!flat) {
            int i = 0;
            for (JsonNode row : arr) {
                out.println(indent + "- [" + i++ + "]");
                render(row, out, indent + "    ");
            }
            return;
        }
        List<String> header = new ArrayList<>(cols);
        List<List<String>> rows = new ArrayList<>();
        for (JsonNode row : arr) {
            List<String> cells = new ArrayList<>();
            for (String c : header) cells.add(row.has(c) ? scalar(row.get(c)) : "");
            rows.add(cells);
        }
        int[] widths = new int[header.size()];
        for (int c = 0; c < header.size(); c++) {
            widths[c] = header.get(c).length();
            for (List<String> row : rows) widths[c] = Math.min(80, Math.max(widths[c], row.get(c).length()));
        }
        StringBuilder sb = new StringBuilder(indent);
        for (int c = 0; c < header.size(); c++) sb.append(pad(header.get(c), widths[c])).append("  ");
        out.println(sb.toString().stripTrailing());
        for (List<String> row : rows) {
            sb = new StringBuilder(indent);
            for (int c = 0; c < header.size(); c++) sb.append(pad(row.get(c), widths[c])).append("  ");
            out.println(sb.toString().stripTrailing());
        }
    }

    private static boolean allScalar(JsonNode arr) {
        for (JsonNode v : arr) if (!v.isValueNode()) return false;
        return true;
    }

    private static boolean isSize(JsonNode v) {
        return v.isObject() && v.has("bytes") && v.has("human");
    }

    private static boolean isObjRef(JsonNode v) {
        return v.isObject() && v.has("addr") && v.has("cls");
    }

    private static String scalar(JsonNode v) {
        if (v == null || v.isNull()) return "";
        if (isSize(v)) return v.get("human").asText() + (v.has("pct") ? " (" + v.get("pct").asText() + "%)" : "");
        if (isObjRef(v)) {
            String s = v.get("cls").asText() + " " + v.get("addr").asText();
            if (v.has("label")) s += " " + v.get("label").asText();
            if (v.has("retained")) s += " retained " + v.get("retained").get("human").asText();
            return s;
        }
        if (v.isObject() && v.path("redacted").asBoolean()) return "<redacted len=" + v.path("len").asText() + ">";
        if (v.isArray()) {
            List<String> parts = new ArrayList<>();
            v.forEach(x -> parts.add(scalar(x)));
            return String.join(", ", parts);
        }
        if (v.isObject()) return v.toString();
        return v.asText();
    }

    private static String pad(String s, int w) {
        if (s.length() > w) return s.substring(0, Math.max(0, w - 1)) + "…";
        return s + " ".repeat(w - s.length());
    }

    /** Adds page.next (the same command with the next offset) when more results exist. */
    static Response withNext(Response r, List<String> argv) {
        if (!r.ok() || r.page() == null) return r;
        var p = r.page();
        if ((long) p.offset() + p.limit() >= p.total()) return r;
        List<String> next = new ArrayList<>();
        for (int i = 0; i < argv.size(); i++) {
            String a = argv.get(i);
            if (a.equals("--offset")) {
                i++;
                continue;
            }
            if (a.startsWith("--offset=")) continue;
            next.add(a.matches("[A-Za-z0-9_@%+=:,./-]+") ? a : "'" + a.replace("'", "'\\''") + "'");
        }
        next.add("--offset");
        next.add(String.valueOf(p.offset() + p.limit()));
        var page = new Response.PageInfo(p.limit(), p.offset(), p.total(), "hd " + String.join(" ", next));
        return new Response(r.ok(), r.engine(), r.data(), page, r.truncated(), r.hints(), r.error());
    }
}
