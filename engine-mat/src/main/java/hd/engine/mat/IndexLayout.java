package hd.engine.mat;

import hd.engine.api.EngineException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Where MAT's index files live. MAT always writes them as {@code <dir>/<dump-stem>.*.index} next to the file it
 * opens; for a separate index directory we open a symlink to the dump placed in that directory.
 *
 * A marker file records which engine version built the indexes; on mismatch the old indexes are deleted.
 */
final class IndexLayout {

    private static final String MARKER_SUFFIX = "hd-engine.properties";

    private IndexLayout() {}

    /** @return the path MAT should open (the dump itself, or a symlink to it inside indexDir) */
    static Path prepare(Path dump, Path indexDir, String engineVersion) {
        Path target = dump;
        try {
            if (indexDir != null) {
                Files.createDirectories(indexDir);
                target = indexDir.resolve(dump.getFileName());
                if (Files.isSymbolicLink(target)) {
                    if (!Files.readSymbolicLink(target).equals(dump)) {
                        Files.delete(target);
                    }
                } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new EngineException(EngineException.Code.IO,
                            "index dir already contains a regular file named " + dump.getFileName() + ": " + target);
                }
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createSymbolicLink(target, dump);
                }
            }
            String built = readMarker(target);
            if (built != null && !built.equals(engineVersion)) {
                deleteIndexes(target);
            } else if (built == null && Files.exists(prefixFile(target, "index"))) {
                // indexes from an unknown builder (e.g. MAT GUI of another version): rebuild to be safe
                deleteIndexes(target);
            }
            return target;
        } catch (IOException e) {
            throw new EngineException(EngineException.Code.IO, "cannot prepare index location: " + e.getMessage(), e);
        }
    }

    /** Whether indexes built by this engine version exist for the dump (without creating anything). */
    static boolean isComplete(Path dump, Path indexDir, String engineVersion) {
        Path target = indexDir == null ? dump : indexDir.resolve(dump.getFileName());
        try {
            return engineVersion.equals(readMarker(target)) && Files.exists(prefixFile(target, "index"));
        } catch (IOException e) {
            return false;
        }
    }

    static void markComplete(Path target, String engineVersion) {
        Properties p = new Properties();
        p.setProperty("engine.version", engineVersion);
        try (OutputStream out = Files.newOutputStream(prefixFile(target, MARKER_SUFFIX))) {
            p.store(out, "written by heap-dump-cli; delete to force re-indexing");
        } catch (IOException e) {
            throw new EngineException(EngineException.Code.IO, "cannot write index marker: " + e.getMessage(), e);
        }
    }

    private static String readMarker(Path target) throws IOException {
        Path marker = prefixFile(target, MARKER_SUFFIX);
        if (!Files.exists(marker)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            p.load(in);
        }
        return p.getProperty("engine.version");
    }

    /** Deletes {@code <prefix>*.index}, {@code <prefix>threads} and our marker. */
    static void deleteIndexes(Path target) throws IOException {
        String prefix = prefix(target).getFileName().toString();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(target.toAbsolutePath().getParent())) {
            for (Path f : files) {
                String n = f.getFileName().toString();
                if (n.startsWith(prefix) && (n.endsWith(".index") || n.equals(prefix + "threads")
                        || n.equals(prefix + MARKER_SUFFIX))) {
                    Files.deleteIfExists(f);
                }
            }
        }
    }

    /** MAT's prefix: the file name with its last extension removed, keeping the dot ("dump.hprof" → "dump."). */
    static Path prefix(Path target) {
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot >= 0 ? name.substring(0, dot + 1) : name + ".";
        return target.toAbsolutePath().getParent().resolve(stem);
    }

    private static Path prefixFile(Path target, String suffix) {
        return Path.of(prefix(target) + suffix);
    }
}
