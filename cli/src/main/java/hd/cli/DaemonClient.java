package hd.cli;

import com.fasterxml.jackson.databind.JsonNode;
import hd.protocol.ErrorCode;
import hd.protocol.HdPaths;
import hd.protocol.Json;
import hd.protocol.JvmOptions;
import hd.protocol.Request;
import hd.protocol.Response;
import hd.protocol.Transport;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/** Talks to the daemon, starting it when needed with the resolved JVM options. */
final class DaemonClient {

    /** Where the effective daemon JVM options came from. */
    record JvmConfig(List<String> opts, String xmx, String source) {}

    private final HdPaths paths;

    DaemonClient(HdPaths paths) {
        this.paths = paths;
    }

    HdPaths paths() {
        return paths;
    }

    boolean isRunning() {
        if (!Files.exists(paths.socket())) return false;
        try {
            return Transport.call(paths.socket(), new Request("status", Map.of())).ok();
        } catch (IOException e) {
            return false;
        }
    }

    /** Sends a request, starting the daemon first if it is not running. */
    Response call(Request request, List<String> cliJvmOpts) {
        if (!isRunning()) {
            Response started = start(cliJvmOpts);
            if (!started.ok()) return started;
        } else if (!cliJvmOpts.isEmpty()) {
            Response mismatch = checkJvmOpts(cliJvmOpts);
            if (mismatch != null) return mismatch;
        }
        return send(request);
    }

    /** Sends without auto-start. */
    Response send(Request request) {
        try {
            return Transport.call(paths.socket(), request);
        } catch (IOException e) {
            return unavailable(e);
        }
    }

    private Response unavailable(IOException e) {
        String log = logTail();
        if (log.contains("OutOfMemoryError")) {
            return Response.error(ErrorCode.ENGINE_OOM,
                    "the analysis daemon ran out of memory and exited; restart it with a larger -Xmx (hd daemon start -Xmx<size>)",
                    Map.of("log", paths.daemonLog().toString()));
        }
        return Response.error(ErrorCode.DAEMON_UNAVAILABLE, "cannot reach the analysis daemon: " + e.getMessage(),
                Map.of("log", paths.daemonLog().toString()));
    }

    // ---- JVM options

    /** Priority: CLI -X options, then HD_JAVA_OPTS, then config daemon.jvm-opts, then automatic sizing. */
    JvmConfig resolve(List<String> cliOpts) {
        if (!cliOpts.isEmpty()) return withXmx(cliOpts, "cli");
        String env = System.getenv("HD_JAVA_OPTS");
        if (env != null && !env.isBlank()) return withXmx(JvmOptions.split(env), "env");
        String cfg = configValue("daemon.jvm-opts");
        if (cfg != null && !cfg.isBlank()) return withXmx(JvmOptions.split(cfg), "config");
        return withXmx(List.of(), "auto");
    }

    private JvmConfig withXmx(List<String> opts, String source) {
        var xmx = JvmOptions.xmx(opts);
        if (xmx.isPresent()) return new JvmConfig(opts, xmx.get(), source);
        List<String> all = new ArrayList<>(opts);
        String auto = autoXmx();
        all.add("-Xmx" + auto);
        return new JvmConfig(all, auto, source.equals("auto") ? "auto" : source + "+auto-xmx");
    }

    /** 75% of physical RAM, leaving at least 2 GiB for the OS, never below 1 GiB. */
    static String autoXmx() {
        long physical = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getTotalMemorySize();
        long gib = 1L << 30;
        long xmx = Math.min((long) (physical * 0.75), physical - 2 * gib);
        return Math.max(1, xmx / gib) + "g";
    }

    private String configValue(String key) {
        if (!Files.isRegularFile(paths.config())) return null;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(paths.config())) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        return p.getProperty(key);
    }

    /** DAEMON_XMX_MISMATCH if the running daemon's -Xmx differs from the requested one; null if fine. */
    private Response checkJvmOpts(List<String> cliOpts) {
        var requested = JvmOptions.xmx(cliOpts);
        if (requested.isEmpty()) return null;
        Response status = send(new Request("status", Map.of()));
        if (!status.ok()) return status;
        JsonNode s = Json.MAPPER.valueToTree(status.data());
        String running = s.path("xmx").asText();
        long want = JvmOptions.parseSize(requested.get());
        long have;
        try {
            have = JvmOptions.parseSize(running);
        } catch (RuntimeException e) {
            have = s.path("xmx_bytes").asLong();
        }
        if (want != have) {
            return new Response(false, null, null, null, null,
                    List.of("hd daemon restart -Xmx" + requested.get() + "   (open sessions are dropped; indexes are kept, reopening is fast)"),
                    new Response.ErrorInfo(ErrorCode.DAEMON_XMX_MISMATCH.name(),
                            "the daemon is running with -Xmx" + running + " but -Xmx" + requested.get() + " was requested",
                            Map.of("running_xmx", running, "requested_xmx", requested.get(),
                                    "running_source", s.path("xmx_source").asText())));
        }
        return null;
    }

    // ---- start / stop

    Response start(List<String> cliOpts) {
        JvmConfig jvm = resolve(cliOpts);
        try {
            Files.createDirectories(paths.home());
        } catch (IOException e) {
            return Response.error(ErrorCode.DAEMON_UNAVAILABLE, "cannot create " + paths.home() + ": " + e.getMessage(), null);
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(jvm.opts());
        cmd.add("-XX:+ExitOnOutOfMemoryError");
        cmd.add("-Dhd.xmx=" + jvm.xmx());
        cmd.add("-Dhd.xmx.source=" + jvm.source());
        cmd.add("-Dhd.home=" + paths.home());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add("hd.daemon.DaemonMain");

        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(paths.daemonLog().toFile()))
                    .redirectErrorStream(true);
            pb.environment().remove("HD_CLIENT_OPTS");
            p = pb.start();
        } catch (IOException e) {
            return Response.error(ErrorCode.DAEMON_UNAVAILABLE, "cannot start the daemon: " + e.getMessage(), null);
        }
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (isRunning()) {
                return Response.ok(null, Map.of("started", true, "pid", p.pid(), "xmx", jvm.xmx(), "xmx_source", jvm.source()), null, null);
            }
            if (!p.isAlive() && p.exitValue() != 0) {
                return Response.error(ErrorCode.DAEMON_UNAVAILABLE, "the daemon exited during startup (exit " + p.exitValue() + ")",
                        Map.of("log_tail", logTail()));
            }
            if (!p.isAlive() && isRunning()) break; // another client's daemon won the race
            sleep(50);
        }
        return isRunning() ? Response.ok(null, Map.of("started", true), null, null)
                : Response.error(ErrorCode.DAEMON_UNAVAILABLE, "the daemon did not come up within 30 s", Map.of("log_tail", logTail()));
    }

    Response stop() {
        if (!isRunning()) return Response.ok(null, Map.of("running", false), null, null);
        Response r = send(new Request("shutdown", Map.of()));
        long deadline = System.currentTimeMillis() + 15_000;
        while (Files.exists(paths.socket()) && System.currentTimeMillis() < deadline) {
            sleep(50);
        }
        return r;
    }

    String logTail() {
        try {
            List<String> lines = Files.readAllLines(paths.daemonLog());
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 20), lines.size()));
        } catch (IOException e) {
            return "";
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
