package hd.protocol;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Helpers for daemon JVM options (-Xmx parsing and comparison). */
public final class JvmOptions {

    private JvmOptions() {}

    /** The last -Xmx in the list (the JVM uses the last one). */
    public static Optional<String> xmx(List<String> opts) {
        String found = null;
        for (String o : opts) {
            if (o.startsWith("-Xmx")) found = o.substring(4);
        }
        return Optional.ofNullable(found);
    }

    /** "48g" → bytes; accepts k/m/g/t suffixes (case-insensitive) or a plain byte count. */
    public static long parseSize(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT);
        long mult = 1;
        char last = v.charAt(v.length() - 1);
        switch (last) {
            case 'k' -> mult = 1L << 10;
            case 'm' -> mult = 1L << 20;
            case 'g' -> mult = 1L << 30;
            case 't' -> mult = 1L << 40;
            default -> {
                return Long.parseLong(v);
            }
        }
        return Long.parseLong(v.substring(0, v.length() - 1)) * mult;
    }

    /** Splits a space-separated option string (no quoting support; JVM options rarely need it). */
    public static List<String> split(String s) {
        if (s == null || s.isBlank()) return List.of();
        return List.of(s.trim().split("\\s+"));
    }
}
