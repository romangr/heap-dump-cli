package hd.analysis;

import java.util.Locale;

/**
 * A byte count rendered for both machines and humans.
 *
 * @param bytes exact value
 * @param human e.g. "4.0 GiB"
 * @param pct percentage of the used heap, or null when not meaningful
 */
public record Size(long bytes, String human, Double pct) {

    public static Size of(long bytes) {
        return new Size(bytes, human(bytes), null);
    }

    public static Size of(long bytes, long heap) {
        Double pct = heap > 0 ? Math.round(bytes * 10000.0 / heap) / 100.0 : null;
        return new Size(bytes, human(bytes), pct);
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, "%.1f %s", v, units[u]);
    }
}
