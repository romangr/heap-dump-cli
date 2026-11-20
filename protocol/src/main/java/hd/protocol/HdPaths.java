package hd.protocol;

import java.nio.file.Path;

/**
 * Locations under the hd home directory ({@code $HD_HOME}, default {@code ~/.hd}).
 */
public record HdPaths(Path home) {

    public static HdPaths fromEnvironment() {
        String env = System.getenv("HD_HOME");
        String prop = System.getProperty("hd.home");
        Path home = prop != null ? Path.of(prop) : env != null ? Path.of(env) : Path.of(System.getProperty("user.home"), ".hd");
        return new HdPaths(home.toAbsolutePath());
    }

    /** Unix socket paths are limited to ~104 bytes (macOS); long homes get a socket in the temp directory. */
    public Path socket() {
        Path p = home.resolve("daemon.sock");
        if (p.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 100) {
            return p;
        }
        String id = Integer.toHexString(home.toString().hashCode());
        return Path.of(System.getProperty("java.io.tmpdir"), "hd-" + id + ".sock");
    }

    /** Written by the running daemon: pid, JVM options, -Xmx and its source. */
    public Path daemonInfo() {
        return home.resolve("daemon.json");
    }

    public Path daemonLog() {
        return home.resolve("daemon.log");
    }

    public Path config() {
        return home.resolve("config.properties");
    }

    /** Central index directory (used when the dump's directory is not writable, or when configured). */
    public Path indexRoot() {
        return home.resolve("index");
    }
}
