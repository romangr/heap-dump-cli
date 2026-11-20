package hd.fixtures;

import java.util.HashMap;
import java.util.Map;

/**
 * Large dump for calibrating the memory estimate and measuring throughput (not part of the normal test fixtures).
 * A map of {@code -Dhd.fixture.entries} (default 15M) entries, each Long → Record(String, int[]): about 5 objects
 * and ~130 bytes per entry, i.e. ~2 GB of heap for the default.
 */
final class LargeScenario implements FixtureMain.Scenario {

    static Map<Long, Record> MAP;

    record Record(String name, int[] data) {}

    @Override
    public void build() {
        int n = Integer.getInteger("hd.fixture.entries", 15_000_000);
        Map<Long, Record> m = new HashMap<>(n * 4 / 3 + 1);
        for (int i = 0; i < n; i++) {
            m.put((long) i, new Record("entry-" + i, new int[i % 4]));
        }
        MAP = m;
    }
}
