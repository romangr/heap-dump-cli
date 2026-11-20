package hd.cli;

import hd.protocol.ErrorCode;
import hd.protocol.Response;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/** {@code hd skill install|status|uninstall}: manage the agent skill for Claude Code, Codex and GitHub Copilot. */
@Command(name = "skill", description = "Install the heap-dump agent skill for Claude Code, Codex and GitHub Copilot.",
        subcommands = {SkillCmd.Install.class, SkillCmd.Status.class, SkillCmd.Uninstall.class})
final class SkillCmd {

    abstract static class Sub implements Callable<Integer> {
        @Mixin Main.OutputOpts output;

        @Option(names = "--target", split = ",", paramLabel = "<agent>",
                description = "claude, codex, copilot or all (default: agents found in your home directory; all with --project)")
        List<String> targets = new ArrayList<>();

        @Option(names = "--project", paramLabel = "<dir>",
                description = "install into this repository (.claude/skills, .agents/skills, .github/skills) instead of your home")
        Path project;

        SkillInstaller installer() {
            return new SkillInstaller(Path.of(System.getProperty("user.home")), Main.version(), launcher());
        }

        Set<SkillInstaller.Target> targets(SkillInstaller inst, boolean forStatus) {
            Set<SkillInstaller.Target> ts = EnumSet.noneOf(SkillInstaller.Target.class);
            for (String t : targets) {
                if (t.equalsIgnoreCase("all")) {
                    ts.addAll(EnumSet.allOf(SkillInstaller.Target.class));
                } else {
                    try {
                        ts.add(SkillInstaller.Target.valueOf(t.trim().toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("unknown target '" + t + "', expected claude, codex, copilot or all");
                    }
                }
            }
            if (ts.isEmpty()) {
                ts = project != null || forStatus ? EnumSet.allOf(SkillInstaller.Target.class) : inst.detect();
            }
            return ts;
        }

        Path projectDir() {
            if (project == null) return null;
            Path p = project.toAbsolutePath().normalize();
            if (!Files.isDirectory(p)) throw new IllegalArgumentException("not a directory: " + p);
            return p;
        }

        int print(Response r) {
            return Output.print(r, output.format, output.compact, System.out);
        }

        int run(Body body) {
            try {
                return print(body.run());
            } catch (IllegalArgumentException e) {
                return print(Response.error(ErrorCode.INVALID_ARGUMENT, e.getMessage(), null));
            } catch (IOException e) {
                return print(Response.error(ErrorCode.FILE_NOT_FOUND, e.getMessage(), null));
            }
        }

        interface Body {
            Response run() throws IOException;
        }
    }

    @Command(name = "install", description = "Install or update the skill (only folders installed by hd are overwritten).")
    static final class Install extends Sub {
        @Option(names = "--force", description = "overwrite folders not installed by hd, or edited after installation")
        boolean force;

        public Integer call() {
            return run(() -> {
                SkillInstaller inst = installer();
                Set<SkillInstaller.Target> ts = targets(inst, false);
                if (ts.isEmpty()) {
                    return Response.error(ErrorCode.INVALID_ARGUMENT,
                            "no supported agent found (~/.claude, ~/.codex, ~/.copilot); choose one with --target", null);
                }
                var results = inst.install(inst.locations(ts, projectDir()), force);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("version", Main.version());
                data.put("locations", results);
                List<String> hints = new ArrayList<>();
                hints.add("restart or reload your agent so it picks up the skill (Copilot: /skills reload)");
                if (project != null && ts.contains(SkillInstaller.Target.CLAUDE) && ts.contains(SkillInstaller.Target.CODEX)) {
                    hints.add("Copilot reads both .claude/skills and .agents/skills, so it may list the skill twice");
                }
                return Response.ok(null, data, null, hints);
            });
        }
    }

    @Command(name = "status", description = "Show where the skill is installed and whether it matches this hd version.")
    static final class Status extends Sub {
        public Integer call() {
            return run(() -> {
                SkillInstaller inst = installer();
                List<SkillInstaller.Status> out = new ArrayList<>();
                for (var loc : inst.locations(targets(inst, true), projectDir())) {
                    out.add(inst.status(loc));
                }
                boolean stale = out.stream().anyMatch(s -> s.state().equals("outdated"));
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("version", Main.version());
                data.put("detected", inst.detect().stream().map(SkillInstaller.Target::id).toList());
                data.put("locations", out);
                return Response.ok(null, data, null, stale ? List.of("hd skill install" + (project != null ? " --project " + project : "")) : null);
            });
        }
    }

    @Command(name = "uninstall", description = "Remove the skill (only folders installed by hd).")
    static final class Uninstall extends Sub {
        @Option(names = "--force", description = "also remove a skill edited after installation") boolean force;

        public Integer call() {
            return run(() -> {
                SkillInstaller inst = installer();
                Set<SkillInstaller.Target> ts = targets(inst, true);
                return Response.ok(null, Map.of("locations", inst.uninstall(inst.locations(ts, projectDir()), force)), null, null);
            });
        }
    }

    /**
     * How agents should run hd: a stable PATH entry if one resolves to this installation (e.g. Homebrew's symlink,
     * which survives upgrades), else the launcher inside the installation, else null (running from an IDE).
     */
    static String launcher() {
        Path own = ownLauncher();
        if (own == null) return null;
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(File.pathSeparator)) {
                Path candidate = Path.of(dir, "hd");
                try {
                    if (Files.isExecutable(candidate) && candidate.toRealPath().equals(own.toRealPath())) {
                        return candidate.toString();
                    }
                } catch (IOException | RuntimeException ignored) {
                    // unreadable PATH entry
                }
            }
        }
        return own.toString();
    }

    /** bin/hd of the installation this class was loaded from (lib/cli.jar → ../bin/hd). */
    static Path ownLauncher() {
        try {
            Path jar = Path.of(SkillCmd.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path bin = jar.getParent().getParent().resolve("bin").resolve("hd");
            return Files.isExecutable(bin) ? bin.toAbsolutePath().normalize() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
