package hd.daemon;

import hd.analysis.Size;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cheap up-front estimate of the daemon heap needed to open a dump, from its file size. No scan of the file.
 * Ratios come from {@link DaemonConfig} (calibrate them with real dumps).
 */
public record MemoryEstimate(long fileBytes, boolean gzip, boolean reopen, long uncompressedBytes, long requiredBytes,
                             long availableBytes, boolean approximate) {

    public boolean fits() {
        return requiredBytes <= availableBytes;
    }

    public static MemoryEstimate of(Path dump, boolean indexesExist, DaemonConfig cfg, long availableBytes) throws IOException {
        long size = Files.size(dump);
        boolean gz;
        try (InputStream in = Files.newInputStream(dump)) {
            gz = in.read() == 0x1f && in.read() == 0x8b;
        }
        long uncompressed = gz ? (long) (size * cfg.gzipExpansion()) : size;
        double ratio = indexesExist ? cfg.reopenRatio() : cfg.memoryRatio();
        long required = (long) (uncompressed * ratio) + 64L * 1024 * 1024; // + baseline for engine and caches
        return new MemoryEstimate(size, gz, indexesExist, uncompressed, required, availableBytes, gz);
    }

    public Map<String, Object> describe() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dump_size", Size.of(fileBytes));
        if (gzip) m.put("uncompressed_size_estimate", Size.of(uncompressedBytes));
        m.put("required_estimate", Size.of(requiredBytes));
        m.put("daemon_available", Size.of(availableBytes));
        m.put("indexes_exist", reopen);
        if (approximate) m.put("note", "gzip dump: uncompressed size is assumed, the estimate is approximate");
        return m;
    }
}
