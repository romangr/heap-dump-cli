package hd.protocol;

import java.util.Map;

/**
 * One command sent to the daemon.
 *
 * @param cmd command name, e.g. "histogram"
 * @param args command arguments (strings, numbers, booleans, lists)
 */
public record Request(String cmd, Map<String, Object> args) {

    public String str(String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : v.toString();
    }

    public String str(String key, String def) {
        String v = str(key);
        return v == null ? def : v;
    }

    public int integer(String key, int def) {
        Object v = args == null ? null : args.get(key);
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number: " + v);
        }
    }

    public boolean bool(String key) {
        Object v = args == null ? null : args.get(key);
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }

    @SuppressWarnings("unchecked")
    public java.util.List<String> list(String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) return java.util.List.of();
        if (v instanceof java.util.List<?> l) return l.stream().map(Object::toString).toList();
        return java.util.Arrays.stream(v.toString().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
