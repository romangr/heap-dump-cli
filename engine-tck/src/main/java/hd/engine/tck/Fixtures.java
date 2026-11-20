package hd.engine.tck;

import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the generated fixture dumps (system property {@code hd.fixtures.dir}, set by the Gradle test task). */
public final class Fixtures {

    private Fixtures() {}

    public static Path dir() {
        String dir = System.getProperty("hd.fixtures.dir");
        if (dir == null) {
            throw new IllegalStateException("system property hd.fixtures.dir is not set; run tests through Gradle");
        }
        return Path.of(dir);
    }

    public static Path dump(String scenario) {
        Path p = dir().resolve(scenario + ".hprof");
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("fixture missing: " + p + " (run ./gradlew :fixtures:generateFixtures)");
        }
        return p;
    }
}
