package hd.fixtures;

import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds a known object graph for one scenario and writes a heap dump of it.
 * Each scenario runs in its own JVM so dumps stay small and independent.
 *
 * Usage: FixtureMain <scenario> <output.hprof>
 */
public final class FixtureMain {

    private static final Map<String, Scenario> SCENARIOS = new TreeMap<>();

    static {
        SCENARIOS.put("graph", new GraphScenario());
        SCENARIOS.put("leaks", new LeaksScenario());
        SCENARIOS.put("large", new LargeScenario());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !SCENARIOS.containsKey(args[0])) {
            System.err.println("usage: FixtureMain <" + String.join("|", SCENARIOS.keySet()) + "> <output.hprof>");
            System.exit(2);
        }
        Scenario scenario = SCENARIOS.get(args[0]);
        Path out = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(out.getParent());
        Files.deleteIfExists(out);

        scenario.build();
        try {
            dump(out);
        } finally {
            scenario.release();
        }
        System.out.println("wrote " + out + " (" + Files.size(out) + " bytes)");
    }

    static void dump(Path out) throws Exception {
        HotSpotDiagnosticMXBean bean = ManagementFactory.newPlatformMXBeanProxy(
                ManagementFactory.getPlatformMBeanServer(),
                "com.sun.management:type=HotSpotDiagnostic",
                HotSpotDiagnosticMXBean.class);
        bean.dumpHeap(out.toString(), true);
    }

    interface Scenario {
        /** Build the object graph; it must stay reachable until {@link #release()}. */
        void build() throws Exception;

        /** Let helper threads finish. */
        default void release() throws Exception {}
    }
}
