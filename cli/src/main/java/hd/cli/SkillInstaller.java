package hd.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Installs the bundled heap-dump skill (Agent Skills format: a folder with SKILL.md) for Claude Code, Codex and
 * GitHub Copilot, per user or per project. Installed folders carry a marker file; folders without it are never
 * overwritten or removed unless forced.
 */
final class SkillInstaller {

    static final String SKILL_NAME = "heap-dump";
    static final String MARKER = ".hd-skill.properties";
    static final String RESOURCE = "/hd/skill/SKILL.md";

    enum Target {
        CLAUDE, CODEX, COPILOT;

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One place a skill folder is (or would be) installed. */
    record Location(Set<Target> targets, Path dir) {}

    /**
     * @param state not-installed, installed, outdated (older hd version), modified (SKILL.md edited after install),
     *              foreign (a heap-dump folder not installed by hd)
     */
    record Status(List<String> targets, String path, String state, String installedVersion) {}

    private final Path home;
    private final String version;
    private final String launcher;

    /**
     * @param launcher command to run hd with, written into the installed skill; null if unknown
     */
    SkillInstaller(Path home, String version, String launcher) {
        this.home = home;
        this.version = version;
        this.launcher = launcher;
    }

    // ---- locations

    /** Agents with a config directory in the user's home. */
    Set<Target> detect() {
        Set<Target> found = EnumSet.noneOf(Target.class);
        if (Files.isDirectory(home.resolve(".claude"))) found.add(Target.CLAUDE);
        if (Files.isDirectory(home.resolve(".codex"))) found.add(Target.CODEX);
        if (Files.isDirectory(home.resolve(".copilot"))) found.add(Target.COPILOT);
        return found;
    }

    /**
     * Personal scope: each agent's own skills directory.
     * Project scope: the minimal set of directories, since Copilot also reads .claude/skills and .agents/skills:
     * Claude → .claude/skills, Codex → .agents/skills, Copilot → whichever of those is written, else .github/skills.
     */
    List<Location> locations(Set<Target> targets, Path project) {
        List<Location> out = new ArrayList<>();
        if (project == null) {
            for (Target t : targets) {
                String dir = switch (t) {
                    case CLAUDE -> ".claude/skills";
                    case CODEX -> ".codex/skills";
                    case COPILOT -> ".copilot/skills";
                };
                out.add(new Location(EnumSet.of(t), home.resolve(dir).resolve(SKILL_NAME)));
            }
            return out;
        }
        boolean copilot = targets.contains(Target.COPILOT);
        if (targets.contains(Target.CLAUDE)) {
            Set<Target> ts = EnumSet.of(Target.CLAUDE);
            if (copilot) ts.add(Target.COPILOT);
            out.add(new Location(ts, project.resolve(".claude/skills").resolve(SKILL_NAME)));
            copilot = false;
        }
        if (targets.contains(Target.CODEX)) {
            Set<Target> ts = EnumSet.of(Target.CODEX);
            if (copilot) ts.add(Target.COPILOT);
            out.add(new Location(ts, project.resolve(".agents/skills").resolve(SKILL_NAME)));
            copilot = false;
        }
        if (copilot) {
            out.add(new Location(EnumSet.of(Target.COPILOT), project.resolve(".github/skills").resolve(SKILL_NAME)));
        }
        return out;
    }

    // ---- operations

    /** @return what was done per location: installed, updated, unchanged, or skipped (foreign/modified, not forced) */
    List<Map<String, Object>> install(List<Location> locations, boolean force) throws IOException {
        byte[] content = render();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Location loc : locations) {
            Status before = status(loc);
            String action;
            if (before.state().equals("foreign") && !force) {
                action = "skipped: folder exists and was not installed by hd (use --force to overwrite)";
            } else if (before.state().equals("modified") && !force) {
                action = "skipped: SKILL.md was edited after installation (use --force to overwrite)";
            } else if (before.state().equals("installed") && sha256(Files.readAllBytes(loc.dir().resolve("SKILL.md"))).equals(sha256(content))) {
                action = "unchanged";
            } else {
                Files.createDirectories(loc.dir());
                Files.write(loc.dir().resolve("SKILL.md"), content);
                writeMarker(loc, content);
                action = before.state().equals("not-installed") ? "installed" : "updated";
            }
            out.add(result(loc, action));
        }
        return out;
    }

    List<Map<String, Object>> uninstall(List<Location> locations, boolean force) throws IOException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Location loc : locations) {
            Status s = status(loc);
            String action;
            switch (s.state()) {
                case "not-installed" -> action = "not installed";
                case "foreign" -> action = "skipped: not installed by hd";
                case "modified" -> {
                    if (!force) {
                        action = "skipped: SKILL.md was edited after installation (use --force to remove)";
                        break;
                    }
                    delete(loc);
                    action = "removed";
                }
                default -> {
                    delete(loc);
                    action = "removed";
                }
            }
            out.add(result(loc, action));
        }
        return out;
    }

    Status status(Location loc) throws IOException {
        List<String> ts = loc.targets().stream().map(Target::id).toList();
        Path skill = loc.dir().resolve("SKILL.md");
        Path marker = loc.dir().resolve(MARKER);
        if (!Files.exists(loc.dir()) || (!Files.exists(skill) && !Files.exists(marker))) {
            return new Status(ts, loc.dir().toString(), "not-installed", null);
        }
        if (!Files.isRegularFile(marker)) {
            return new Status(ts, loc.dir().toString(), "foreign", null);
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(marker)) {
            p.load(in);
        }
        String installed = p.getProperty("version");
        if (!Files.isRegularFile(skill) || !sha256(Files.readAllBytes(skill)).equals(p.getProperty("sha256"))) {
            return new Status(ts, loc.dir().toString(), "modified", installed);
        }
        String state = version.equals(installed) && sha256(render()).equals(p.getProperty("sha256")) ? "installed" : "outdated";
        return new Status(ts, loc.dir().toString(), state, installed);
    }

    // ---- content

    /** The bundled SKILL.md plus an installation note (version, launcher path). */
    byte[] render() throws IOException {
        String base;
        try (InputStream in = SkillInstaller.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("skill resource " + RESOURCE + " missing from the hd installation");
            base = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder sb = new StringBuilder(base.stripTrailing()).append("\n\n## Installation\n\n");
        sb.append("Installed by hd ").append(version).append(". After upgrading hd, run `hd skill install` again.\n");
        if (launcher != null) {
            sb.append("If `hd` is not on PATH, run it as `").append(launcher).append("`.\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void writeMarker(Location loc, byte[] content) throws IOException {
        Properties p = new Properties();
        p.setProperty("version", version);
        p.setProperty("sha256", sha256(content));
        p.setProperty("targets", String.join(",", loc.targets().stream().map(Target::id).toList()));
        try (OutputStream out = Files.newOutputStream(loc.dir().resolve(MARKER))) {
            p.store(out, "installed by hd skill install; remove with hd skill uninstall");
        }
    }

    private static void delete(Location loc) throws IOException {
        Files.deleteIfExists(loc.dir().resolve("SKILL.md"));
        Files.deleteIfExists(loc.dir().resolve(MARKER));
        try (var rest = Files.list(loc.dir())) {
            if (rest.findAny().isEmpty()) Files.delete(loc.dir());
        }
    }

    private static Map<String, Object> result(Location loc, String action) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("targets", loc.targets().stream().map(Target::id).toList());
        m.put("path", loc.dir().toString());
        m.put("result", action);
        return m;
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
