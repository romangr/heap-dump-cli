package hd.daemon;

import hd.protocol.HdPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * Settings from {@code ~/.hd/config.properties}.
 *
 * @param idleTimeout shut down after this long without requests or running work
 * @param indexDir if set, all indexes go there (per-dump subdirectories); else next to the dump when writable
 * @param memoryRatio estimated daemon heap needed per byte of (uncompressed) dump for a first parse. Calibrated on
 *                    a 3.6 GB dump of 90M small objects (worst case: many objects per byte): fails at 0.56, works at
 *                    0.69; dumps with larger objects need less
 * @param reopenRatio same, when indexes already exist (same dump reopened and queried with 0.14)
 * @param indexDiskRatio free disk needed for indexes per byte of uncompressed dump (measured: 1.4)
 * @param gzipExpansion assumed uncompressed/compressed size ratio for .gz dumps
 * @param maxSessions how many snapshots may be loaded at once (least recently used is closed first)
 */
public record DaemonConfig(HdPaths paths, Duration idleTimeout, Path indexDir, double memoryRatio, double reopenRatio,
                           double indexDiskRatio, double gzipExpansion, int maxSessions, String engine) {

    public static DaemonConfig load(HdPaths paths) {
        Properties p = new Properties();
        if (Files.isRegularFile(paths.config())) {
            try (InputStream in = Files.newInputStream(paths.config())) {
                p.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + paths.config() + ": " + e.getMessage(), e);
            }
        }
        String indexDir = p.getProperty("index.dir");
        return new DaemonConfig(paths,
                Duration.ofMinutes(Long.parseLong(p.getProperty("daemon.idle-timeout-minutes", "30"))),
                indexDir == null || indexDir.isBlank() ? null : Path.of(indexDir),
                Double.parseDouble(p.getProperty("memory.ratio", "0.75")),
                Double.parseDouble(p.getProperty("memory.reopen-ratio", "0.25")),
                Double.parseDouble(p.getProperty("disk.index-ratio", "1.5")),
                Double.parseDouble(p.getProperty("memory.gzip-expansion", "4.0")),
                Integer.parseInt(p.getProperty("daemon.max-sessions", "1")),
                p.getProperty("engine", "mat"));
    }
}
