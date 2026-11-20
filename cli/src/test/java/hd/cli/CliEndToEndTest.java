package hd.cli;

import com.fasterxml.jackson.databind.JsonNode;
import hd.engine.tck.Fixtures;
import hd.protocol.Json;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs the installed {@code hd} launcher (build/install/hd) against a private HD_HOME: real daemon, real socket.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CliEndToEndTest {

    Path home;
    Path dumps;
    String session;

    record Result(int exit, JsonNode json, String raw) {
        JsonNode data() {
            return json.path("data");
        }

        String code() {
            return json.path("error").path("code").asText();
        }
    }

    Result hd(String... args) throws Exception {
        Path bin = Path.of(System.getProperty("hd.install.dir"), "bin", "hd");
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.toString());
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("HD_HOME", home.toString());
        pb.environment().remove("HD_JAVA_OPTS");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(2, TimeUnit.MINUTES), "hd " + String.join(" ", args) + " timed out");
        JsonNode json;
        try {
            json = Json.MAPPER.readTree(out);
        } catch (IOException e) {
            json = null;
        }
        return new Result(p.exitValue(), json, out);
    }

    @BeforeAll
    void setUp() throws Exception {
        home = Files.createTempDirectory("hd-e2e");
        // copy fixtures so indexes are written into a private directory
        dumps = Files.createDirectories(home.resolve("dumps"));
        for (String f : List.of("graph", "leaks")) {
            Files.copy(Fixtures.dump(f), dumps.resolve(f + ".hprof"));
        }
        Result open = hd("open", dumps.resolve("leaks.hprof").toString(), "-Xmx512m", "--wait", "2m");
        assertEquals(0, open.exit(), open.raw());
        assertEquals("ready", open.data().path("state").asText(), open.raw());
        session = open.data().path("session").asText();
        assertTrue(session.startsWith("s:"));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (home != null) {
            hd("daemon", "stop");
            try (var files = Files.walk(home)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void statusReportsXmxAndSource() throws Exception {
        Result r = hd("daemon", "status");
        assertEquals(0, r.exit(), r.raw());
        assertEquals("512m", r.data().path("xmx").asText());
        assertEquals("cli", r.data().path("xmx_source").asText());
        assertEquals(1, r.data().path("sessions").size());
    }

    @Test
    void xmxMismatchIsAnError() throws Exception {
        Result r = hd("open", dumps.resolve("graph.hprof").toString(), "-Xmx1g");
        assertEquals(5, r.exit(), r.raw());
        assertEquals("DAEMON_XMX_MISMATCH", r.code());
        assertTrue(r.json().path("hints").get(0).asText().startsWith("hd daemon restart -Xmx1g"));
        // the same -Xmx is fine
        Result same = hd("sessions", "--compact");
        assertEquals(0, same.exit());
    }

    @Test
    void infoAndDetect() throws Exception {
        Result info = hd("info", session);
        assertEquals(0, info.exit(), info.raw());
        assertEquals("mat", info.json().path("engine").asText());
        assertTrue(info.data().path("topRetainers").size() > 0);

        Result detect = hd("detect", session);
        assertEquals(0, detect.exit(), detect.raw());
        List<String> detectors = new ArrayList<>();
        detect.data().path("findings").forEach(f -> detectors.add(f.path("detector").asText()));
        assertTrue(detectors.containsAll(List.of("threadlocal", "classloader", "collections", "direct-buffers", "big-retainers")), detectors.toString());
        for (JsonNode f : detect.data().path("findings")) {
            for (JsonNode n : f.path("next")) {
                assertTrue(n.asText().contains(session), "session substituted in next commands: " + n);
            }
        }
    }

    @Test
    void pagingProducesNextCommand() throws Exception {
        Result r = hd("histogram", session, "--filter", "^hd\\.", "--limit", "2");
        assertEquals(0, r.exit(), r.raw());
        JsonNode page = r.json().path("page");
        assertEquals(2, page.path("limit").asInt());
        assertTrue(page.path("total").asInt() > 2);
        assertEquals("hd histogram " + session + " --filter '^hd\\.' --limit 2 --offset 2", page.path("next").asText());
    }

    @Test
    void longQueryReturnsJobThenWaitDeliversResult() throws Exception {
        Result pending = hd("histogram", session, "--sort", "retained", "--timeout", "0s");
        assertEquals(0, pending.exit(), pending.raw());
        String job;
        if (pending.data().has("job")) {
            job = pending.data().path("job").asText();
            assertEquals("hd wait " + job, pending.json().path("hints").get(0).asText());
            Result done = hd("wait", job);
            assertEquals(0, done.exit(), done.raw());
            assertEquals("retained", done.data().path("sort").asText());
        } else {
            // finished instantly on a fast machine: still a valid result
            assertEquals("retained", pending.data().path("sort").asText());
        }
    }

    @Test
    void pathsThreadsAndRedaction() throws Exception {
        Result threads = hd("threads", session, "--limit", "1");
        assertEquals("local-holder", threads.data().path("threads").path("items").get(0).path("name").asText(), threads.raw());

        Result oql = hd("oql", session, "SELECT toString(t.name) FROM java.lang.Thread t", "--limit", "3");
        assertTrue(oql.data().path("rows").path("items").get(0).get(0).path("redacted").asBoolean(), oql.raw());
        Result shown = hd("oql", session, "SELECT toString(t.name) FROM java.lang.Thread t", "--limit", "50", "--show-values");
        assertTrue(shown.raw().contains("\"local-holder\""), shown.raw());

        String leaky = hd("instances", session, "hd.fixtures.model.LeakyValue", "--limit", "1")
                .data().path("instances").path("items").get(0).path("addr").asText();
        Result path = hd("path-to-roots", session, leaky);
        assertEquals(0, path.exit(), path.raw());
        JsonNode steps = path.data().path("paths").get(0).path("path");
        assertEquals("java.lang.Thread", steps.get(steps.size() - 1).path("obj").path("cls").asText());
        assertEquals("threadLocals", steps.get(steps.size() - 1).path("via").asText());
    }

    @Test
    void errorCodesAndExitCodes() throws Exception {
        assertEquals(Map.entry(4, "SESSION_NOT_FOUND"), codeOf(hd("info", "s:000000")));
        assertEquals(Map.entry(4, "OBJECT_NOT_FOUND"), codeOf(hd("object", session, "0x10")));
        assertEquals(Map.entry(2, "INVALID_ARGUMENT"), codeOf(hd("object", session, "zzz")));
        assertEquals(Map.entry(2, "INVALID_ARGUMENT"), codeOf(hd("histogram", session, "--nope")));
        assertEquals(Map.entry(4, "FILE_NOT_FOUND"), codeOf(hd("open", home.resolve("missing.hprof").toString())));
    }

    private static Map.Entry<Integer, String> codeOf(Result r) {
        assertNotNull(r.json(), "JSON expected: " + r.raw());
        assertFalse(r.json().path("ok").asBoolean());
        return Map.entry(r.exit(), r.code());
    }

    @Test
    void triage() throws Exception {
        Result r = hd("triage", dumps.resolve("graph.hprof").toString(), "--limit", "5");
        assertEquals(0, r.exit(), r.raw());
        assertEquals("hprof-stream", r.json().path("engine").asText());
        assertEquals(5, r.data().path("classes").path("items").size());
        assertTrue(r.data().path("objects").asLong() > 1000);
    }

    @Test
    void skillInstallIntoProject() throws Exception {
        Path repo = Files.createDirectories(home.resolve("repo"));
        Result install = hd("skill", "install", "--project", repo.toString());
        assertEquals(0, install.exit(), install.raw());
        Path claude = repo.resolve(".claude/skills/heap-dump/SKILL.md");
        Path agents = repo.resolve(".agents/skills/heap-dump/SKILL.md");
        assertTrue(Files.isRegularFile(claude) && Files.isRegularFile(agents), install.raw());
        String skill = Files.readString(claude);
        assertTrue(skill.startsWith("---\nname: heap-dump"), skill);
        Path launcher = Path.of(System.getProperty("hd.install.dir"), "bin", "hd").toRealPath();
        assertTrue(skill.contains(launcher.toString()) || skill.contains("`hd`"), "launcher path recorded: " + skill);

        Result status = hd("skill", "status", "--project", repo.toString());
        assertEquals(0, status.exit());
        status.data().path("locations").forEach(l -> assertEquals("installed", l.path("state").asText(), status.raw()));

        Result again = hd("skill", "install", "--project", repo.toString(), "--target", "codex");
        assertEquals("unchanged", again.data().path("locations").get(0).path("result").asText(), again.raw());

        Result bad = hd("skill", "install", "--project", repo.toString(), "--target", "vim");
        assertEquals(2, bad.exit(), bad.raw());

        Result removed = hd("skill", "uninstall", "--project", repo.toString());
        assertEquals(0, removed.exit());
        assertFalse(Files.exists(claude));
        assertFalse(Files.exists(agents));
    }

    @Test
    void versionFromManifest() throws Exception {
        Path bin = Path.of(System.getProperty("hd.install.dir"), "bin", "hd");
        Process p = new ProcessBuilder(bin.toString(), "--version").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        assertTrue(out.matches("hd \\d+\\.\\d+\\.\\d+.*"), out);
    }

    @Test
    void tableFormat() throws Exception {
        Result r = hd("dominators", session, "--limit", "3", "--format", "table");
        assertEquals(0, r.exit());
        assertTrue(r.raw().contains("retained"), r.raw());
        assertTrue(r.raw().contains("(1-3 of"), r.raw());
    }
}
