package hd.hprof;

import hd.engine.api.Cancel;
import hd.engine.api.EngineException;
import hd.engine.api.OpenOptions;
import hd.engine.api.Progress;
import hd.engine.api.Snapshot;
import hd.engine.api.capability.FastHistogram;
import hd.engine.mat.MatEngine;
import hd.engine.tck.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class TriageTest {

    @TempDir
    Path tmp;

    private TriageResult triage(Path dump) throws IOException {
        return Triage.run(dump, Triage.Options.defaults(), Progress.NONE, Cancel.none());
    }

    @Test
    void countsMatchMatHistogram() throws Exception {
        for (String scenario : List.of("graph", "leaks")) {
            Path dump = Fixtures.dump(scenario);
            TriageResult t = triage(dump);
            Map<String, TriageResult.ClassRow> ours = t.classes().stream()
                    .collect(Collectors.toMap(TriageResult.ClassRow::className, Function.identity(), (a, b) ->
                            new TriageResult.ClassRow(a.className(), a.count() + b.count(), a.estimatedBytes() + b.estimatedBytes())));
            try (Snapshot s = new MatEngine().open(dump, new OpenOptions(tmp.resolve(scenario), false), Progress.NONE, Cancel.none())) {
                List<FastHistogram.Row> mat = s.capability(FastHistogram.class).orElseThrow().histogram(Cancel.none());
                Map<String, long[]> matByName = mat.stream().collect(Collectors.toMap(FastHistogram.Row::className,
                        r -> new long[]{r.count(), r.shallow()}, (a, b) -> new long[]{a[0] + b[0], a[1] + b[1]}));

                for (String cls : List.of("hd.fixtures.model.ChainNode", "hd.fixtures.model.DiamondNode",
                        "hd.fixtures.model.LeakyValue", "java.util.HashMap", "java.lang.String", "byte[]", "java.lang.Object[]")) {
                    if (!matByName.containsKey(cls)) continue;
                    long[] m = matByName.get(cls);
                    TriageResult.ClassRow o = ours.get(cls);
                    assertNotNull(o, scenario + ": triage misses " + cls);
                    // MAT discards unreachable objects, triage counts everything in the file
                    if (cls.startsWith("hd.fixtures.")) {
                        assertEquals(m[0], o.count(), scenario + " count of " + cls);
                    } else {
                        assertTrue(o.count() >= m[0] && o.count() <= m[0] * 1.05 + 2,
                                scenario + " count of " + cls + ": mat=" + m[0] + " triage=" + o.count());
                    }
                    double matAvg = (double) m[1] / m[0];
                    double ourAvg = (double) o.estimatedBytes() / o.count();
                    assertEquals(matAvg, ourAvg, matAvg * 0.05, scenario + " average shallow size of " + cls);
                }
                // triage includes objects not reachable from GC roots, which MAT discards by default
                assertTrue(t.objects() >= s.info().objectCount(), scenario + " object count");
            }
        }
    }

    @Test
    void biggestArraysAndThreads() throws Exception {
        TriageResult t = triage(Fixtures.dump("leaks"));
        assertEquals(20, t.biggestArrays().size());
        TriageResult.ArrayRow top = t.biggestArrays().get(0);
        assertTrue(top.estimatedBytes() >= t.biggestArrays().get(1).estimatedBytes());
        assertTrue(t.biggestArrays().stream().anyMatch(a -> a.type().equals("byte[]") && a.length() == 2 * 1024 * 1024),
                "the 2 MiB local of thread local-holder is among the biggest arrays");
        assertTrue(t.biggestArrays().stream().anyMatch(a -> a.type().equals("java.util.HashMap$Node[]") && a.length() == 1 << 20),
                "the sparse 1M-bucket HashMap table is among the biggest arrays");

        assertTrue(t.gcRoots().getOrDefault("THREAD_OBJECT", 0L) >= 6);
        assertTrue(t.threads().stream().anyMatch(th -> th.frames().stream()
                .anyMatch(f -> f.method().equals("hd.fixtures.LeaksScenario.holdLocal") && f.location() != null
                        && f.location().startsWith("LeaksScenario.java:"))));
        assertTrue(t.strings().count() >= 5000);
        assertEquals(8, t.identifierSize());
    }

    @Test
    void gzipAndProgress() throws Exception {
        Path gz = tmp.resolve("graph.hprof.gz");
        try (InputStream in = Files.newInputStream(Fixtures.dump("graph"));
             OutputStream out = new GZIPOutputStream(Files.newOutputStream(gz))) {
            in.transferTo(out);
        }
        List<Double> fractions = new CopyOnWriteArrayList<>();
        TriageResult z = Triage.run(gz, Triage.Options.defaults(), (p, f) -> fractions.add(f), Cancel.none());
        TriageResult plain = triage(Fixtures.dump("graph"));
        assertTrue(z.gzip());
        assertEquals(plain.objects(), z.objects());
        assertEquals(plain.classes(), z.classes());
        assertFalse(fractions.isEmpty());
        assertEquals(1.0, fractions.get(fractions.size() - 1));
    }

    @Test
    void cancel() {
        Cancel c = new Cancel();
        c.cancel();
        var e = assertThrows(EngineException.class, () -> Triage.run(Fixtures.dump("leaks"), Triage.Options.defaults(), Progress.NONE, c));
        assertEquals(EngineException.Code.CANCELLED, e.code());
    }

    @Test
    void notAnHprof() throws Exception {
        Path bad = tmp.resolve("x.hprof");
        Files.writeString(bad, "hello world, definitely not a heap dump\0");
        assertThrows(HprofFormatException.class, () -> triage(bad));
    }

    @Test
    void truncated() throws Exception {
        Path cut = tmp.resolve("cut.hprof");
        byte[] all = Files.readAllBytes(Fixtures.dump("graph"));
        Files.write(cut, java.util.Arrays.copyOf(all, all.length / 2));
        assertThrows(java.io.EOFException.class, () -> triage(cut));
    }

    @Test
    void javaNames() {
        assertEquals("java.lang.Object[]", TriageNames.javaName("[Ljava/lang/Object;"));
        assertEquals("int[][]", TriageNames.javaName("[[I"));
        assertEquals("java.util.HashMap$Node", TriageNames.javaName("java/util/HashMap$Node"));
    }
}
