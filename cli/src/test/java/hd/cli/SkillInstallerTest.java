package hd.cli;

import hd.cli.SkillInstaller.Target;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SkillInstallerTest {

    @TempDir
    Path home;

    @TempDir
    Path project;

    SkillInstaller installer(String version) {
        return new SkillInstaller(home, version, "/opt/hd/bin/hd");
    }

    private static String result(List<Map<String, Object>> r, int i) {
        return (String) r.get(i).get("result");
    }

    @Test
    void detectsAgentsByConfigDirectory() throws Exception {
        assertEquals(Set.of(), installer("1").detect());
        Files.createDirectories(home.resolve(".claude"));
        Files.createDirectories(home.resolve(".codex"));
        assertEquals(EnumSet.of(Target.CLAUDE, Target.CODEX), installer("1").detect());
    }

    @Test
    void personalLocations() {
        var locs = installer("1").locations(EnumSet.allOf(Target.class), null);
        assertEquals(List.of(home.resolve(".claude/skills/heap-dump"), home.resolve(".codex/skills/heap-dump"),
                home.resolve(".copilot/skills/heap-dump")), locs.stream().map(SkillInstaller.Location::dir).toList());
    }

    @Test
    void projectLocationsAvoidExtraCopiesForCopilot() {
        var inst = installer("1");
        var all = inst.locations(EnumSet.allOf(Target.class), project);
        assertEquals(List.of(project.resolve(".claude/skills/heap-dump"), project.resolve(".agents/skills/heap-dump")),
                all.stream().map(SkillInstaller.Location::dir).toList());
        assertEquals(EnumSet.of(Target.CLAUDE, Target.COPILOT), all.get(0).targets(), "Copilot reads .claude/skills");

        var codexCopilot = inst.locations(EnumSet.of(Target.CODEX, Target.COPILOT), project);
        assertEquals(List.of(project.resolve(".agents/skills/heap-dump")), codexCopilot.stream().map(SkillInstaller.Location::dir).toList());

        var copilotOnly = inst.locations(EnumSet.of(Target.COPILOT), project);
        assertEquals(List.of(project.resolve(".github/skills/heap-dump")), copilotOnly.stream().map(SkillInstaller.Location::dir).toList());
    }

    @Test
    void installRendersSkillWithLauncherAndIsIdempotent() throws Exception {
        var inst = installer("0.1.0");
        var locs = inst.locations(EnumSet.of(Target.CODEX), null);
        assertEquals("installed", result(inst.install(locs, false), 0));
        String skill = Files.readString(locs.get(0).dir().resolve("SKILL.md"));
        assertTrue(skill.startsWith("---\nname: heap-dump\n"), "frontmatter must stay first");
        assertTrue(skill.contains("Installed by hd 0.1.0"));
        assertTrue(skill.contains("`/opt/hd/bin/hd`"));
        assertEquals("installed", inst.status(locs.get(0)).state());
        assertEquals("unchanged", result(inst.install(locs, false), 0));
    }

    @Test
    void upgradeDetectionAndUpdate() throws Exception {
        var locs = installer("0.1.0").locations(EnumSet.of(Target.CLAUDE), null);
        installer("0.1.0").install(locs, false);
        var newer = installer("0.2.0");
        var st = newer.status(locs.get(0));
        assertEquals("outdated", st.state());
        assertEquals("0.1.0", st.installedVersion());
        assertEquals("updated", result(newer.install(locs, false), 0));
        assertEquals("installed", newer.status(locs.get(0)).state());
    }

    @Test
    void neverOverwritesForeignOrEditedSkillsWithoutForce() throws Exception {
        var inst = installer("1");
        var locs = inst.locations(EnumSet.of(Target.CLAUDE), null);
        Path dir = locs.get(0).dir();
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "someone else's skill");
        assertEquals("foreign", inst.status(locs.get(0)).state());
        assertTrue(result(inst.install(locs, false), 0).startsWith("skipped"));
        assertEquals("someone else's skill", Files.readString(dir.resolve("SKILL.md")));
        assertTrue(result(inst.uninstall(locs, true), 0).startsWith("skipped"), "foreign folders are never removed");

        assertEquals("updated", result(inst.install(locs, true), 0));
        Files.writeString(dir.resolve("SKILL.md"), "\nlocal edit", java.nio.file.StandardOpenOption.APPEND);
        assertEquals("modified", inst.status(locs.get(0)).state());
        assertTrue(result(inst.install(locs, false), 0).startsWith("skipped"));
        assertTrue(result(inst.uninstall(locs, false), 0).startsWith("skipped"));
        assertEquals("removed", result(inst.uninstall(locs, true), 0));
        assertFalse(Files.exists(dir));
    }

    @Test
    void uninstallKeepsOtherFilesInTheFolder() throws Exception {
        var inst = installer("1");
        var locs = inst.locations(EnumSet.of(Target.COPILOT), project);
        inst.install(locs, false);
        Files.writeString(locs.get(0).dir().resolve("notes.txt"), "mine");
        assertEquals("removed", result(inst.uninstall(locs, false), 0));
        assertTrue(Files.exists(locs.get(0).dir().resolve("notes.txt")));
        assertFalse(Files.exists(locs.get(0).dir().resolve("SKILL.md")));
        assertEquals("not installed", result(inst.uninstall(locs, false), 0));
    }
}
