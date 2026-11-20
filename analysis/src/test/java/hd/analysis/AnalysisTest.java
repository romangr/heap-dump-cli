package hd.analysis;

import hd.analysis.detect.DetectContext;
import hd.analysis.detect.Detectors;
import hd.analysis.detect.Finding;
import hd.analysis.view.DominatorView;
import hd.analysis.view.HistogramView;
import hd.analysis.view.InfoView;
import hd.analysis.view.InstancesView;
import hd.analysis.view.ObjectInspector;
import hd.analysis.view.OqlView;
import hd.analysis.view.PathsView;
import hd.analysis.view.RefsView;
import hd.analysis.view.ThreadsView;
import hd.engine.api.Addresses;
import hd.engine.api.Cancel;
import hd.engine.api.OpenOptions;
import hd.engine.api.Progress;
import hd.engine.api.Snapshot;
import hd.engine.api.capability.FastGcRootPaths.RefKind;
import hd.engine.mat.MatEngine;
import hd.engine.tck.Fixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalysisTest {

    @TempDir
    Path indexDir;

    Snapshot graphSnap, leaksSnap;
    Heap graph, leaks;

    @BeforeAll
    void open() {
        MatEngine engine = new MatEngine();
        graphSnap = engine.open(Fixtures.dump("graph"), new OpenOptions(indexDir, false), Progress.NONE, Cancel.none());
        leaksSnap = engine.open(Fixtures.dump("leaks"), new OpenOptions(indexDir, false), Progress.NONE, Cancel.none());
        graph = new Heap(graphSnap);
        leaks = new Heap(leaksSnap);
    }

    @AfterAll
    void close() {
        graphSnap.close();
        leaksSnap.close();
    }

    private Map<String, Long> diamonds() {
        long cls = graphSnap.classesByName("hd.fixtures.model.DiamondNode").get(0).address();
        Map<String, Long> m = new HashMap<>();
        graphSnap.instancesOf(cls).forEach(a ->
                m.put(JdkLayouts.metadataString(graph, graph.object(a).refField("name")), a));
        return m;
    }

    private long chainHead() {
        long root = graphSnap.instancesOf(graphSnap.classesByName("hd.fixtures.model.FixtureRoot").get(0).address()).findFirst().orElseThrow();
        return graph.object(root).refField("chain");
    }

    // ---- views

    @Test
    void histogram() {
        var byClass = HistogramView.compute(graph, HistogramView.By.CLASS, Pattern.compile("^hd\\.fixtures\\.model\\."),
                HistogramView.Sort.COUNT, Limits.defaults(), Progress.NONE, Cancel.none());
        var chain = byClass.rows().items().stream().filter(r -> r.name().equals("hd.fixtures.model.ChainNode")).findFirst().orElseThrow();
        assertEquals(10, chain.count());
        assertNotNull(chain.classAddr());
        assertTrue(byClass.rows().items().stream().allMatch(r -> r.name().startsWith("hd.fixtures.model.")));

        var byPackage = HistogramView.compute(graph, HistogramView.By.PACKAGE, Pattern.compile("^hd\\.fixtures"),
                HistogramView.Sort.SHALLOW, Limits.defaults(), Progress.NONE, Cancel.none());
        assertTrue(byPackage.rows().items().stream().anyMatch(r -> r.name().equals("hd.fixtures.model")));

        var retained = HistogramView.compute(graph, HistogramView.By.CLASS, null, HistogramView.Sort.RETAINED,
                Limits.first(10), Progress.NONE, Cancel.none());
        assertNotNull(retained.retainedNote());
        var root = retained.rows().items().stream().filter(r -> r.name().equals("hd.fixtures.model.FixtureRoot")).findFirst();
        assertTrue(root.isPresent(), "FixtureRoot retains > 1 MiB and must be in the top 10 by retained");
        assertTrue(root.get().retained().bytes() >= 1 << 20);

        var paged = HistogramView.compute(graph, HistogramView.By.CLASS, null, HistogramView.Sort.SHALLOW,
                new Limits(5, 5), Progress.NONE, Cancel.none());
        assertEquals(5, paged.rows().items().size());
        assertTrue(paged.rows().total() > 100);
    }

    @Test
    void dominators() {
        var top = DominatorView.children(graph, Snapshot.ROOT, false, Limits.first(10), Redaction.ON, Cancel.none());
        var rows = top.rows().items();
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i - 1).retained().bytes() >= rows.get(i).retained().bytes(), "sorted by retained");
        }
        Map<String, Long> d = diamonds();
        var kids = DominatorView.children(graph, d.get("top"), false, Limits.defaults(), Redaction.ON, Cancel.none());
        assertEquals(Set.of(Addresses.hex(d.get("left")), Addresses.hex(d.get("right")), Addresses.hex(d.get("bottom"))),
                kids.rows().items().stream().filter(r -> r.cls().equals("hd.fixtures.model.DiamondNode"))
                        .map(ObjRef::addr).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Addresses.hex(d.get("bottom")), kids.rows().items().get(0).addr(), "bottom (4 KiB payload) retains most");

        var grouped = DominatorView.children(graph, d.get("top"), true, Limits.defaults(), Redaction.ON, Cancel.none());
        var g = grouped.groups().items().stream().filter(x -> x.cls().equals("hd.fixtures.model.DiamondNode")).findFirst().orElseThrow();
        assertEquals(3, g.count());
    }

    @Test
    void objectInspectionAndRedaction() {
        long head = chainHead();
        var r = ObjectInspector.inspect(graph, head, true, Limits.defaults(), Redaction.ON);
        assertEquals("instance", r.kind());
        var index = r.fields().stream().filter(f -> f.name().equals("index")).findFirst().orElseThrow();
        assertEquals(0, index.value());
        var next = r.fields().stream().filter(f -> f.name().equals("next")).findFirst().orElseThrow();
        assertInstanceOf(ObjRef.class, next.value());
        assertNotNull(r.dominator());

        long top = diamonds().get("top");
        var hidden = ObjectInspector.inspect(graph, top, true, Limits.defaults(), Redaction.ON);
        ObjRef name = (ObjRef) hidden.fields().stream().filter(f -> f.name().equals("name")).findFirst().orElseThrow().value();
        assertEquals("len=3 (value redacted)", name.label());
        var shown = ObjectInspector.inspect(graph, top, true, Limits.defaults(), Redaction.OFF);
        ObjRef shownName = (ObjRef) shown.fields().stream().filter(f -> f.name().equals("name")).findFirst().orElseThrow().value();
        assertEquals("\"top\"", shownName.label());

        long data = graph.object(graph.object(head).refField("payload")).refField("data");
        var bytes = ObjectInspector.inspect(graph, data, true, Limits.first(8), Redaction.ON);
        assertEquals(1000, bytes.length());
        assertEquals(List.of(new Redaction.Redacted(1000)), bytes.elements().items());
        var bytesShown = ObjectInspector.inspect(graph, data, true, Limits.first(8), Redaction.OFF);
        assertEquals(8, bytesShown.elements().items().size());
        assertEquals((byte) 0, bytesShown.elements().items().get(0));
    }

    @Test
    void refs() {
        Map<String, Long> d = diamonds();
        var in = RefsView.refs(graph, d.get("bottom"), RefsView.Direction.IN, Limits.defaults(), Redaction.ON, Cancel.none());
        assertEquals(Set.of(Addresses.hex(d.get("left")), Addresses.hex(d.get("right"))),
                in.refs().items().stream().map(ObjRef::addr).collect(java.util.stream.Collectors.toSet()));
        var out = RefsView.refs(graph, d.get("top"), RefsView.Direction.OUT, Limits.defaults(), Redaction.ON, Cancel.none());
        assertTrue(out.refs().total() >= 3);
    }

    @Test
    void pathsToRoots() {
        Map<String, Long> d = diamonds();
        var r = PathsView.toRoots(graph, d.get("bottom"), Set.of(RefKind.WEAK, RefKind.SOFT), 5, Redaction.ON, Cancel.none());
        assertTrue(r.engineNative());
        assertFalse(r.isRoot());
        int paths = r.paths().stream().mapToInt(PathsView.PathGroup::count).sum();
        assertTrue(paths >= 2);
        for (var g : r.paths()) {
            var steps = g.path();
            assertEquals(Addresses.hex(d.get("bottom")), steps.get(0).obj().addr());
            assertNull(steps.get(0).via());
            assertEquals("a", steps.get(1).via(), "left.a / right.a reference bottom");
            assertFalse(steps.get(steps.size() - 1).rootTypes().isEmpty());
        }

        // engine-independent fallback finds equally short paths
        List<long[]> bfs = PathsView.bfs(graph, d.get("bottom"), Set.of(RefKind.WEAK), 3, Cancel.none());
        assertFalse(bfs.isEmpty());
        long[] p = bfs.get(0);
        assertEquals(d.get("bottom"), p[0]);
        assertFalse(graphSnap.rootTypes(p[p.length - 1]).isEmpty());
        int nativeShortest = r.paths().stream().mapToInt(x -> x.path().size()).min().orElseThrow();
        assertEquals(nativeShortest, p.length);
    }

    @Test
    void instances() {
        var r = InstancesView.list(graph, "hd.fixtures.model.ChainNode", InstancesView.Sort.RETAINED, Limits.first(3), Redaction.ON, Cancel.none());
        assertEquals(10, r.instances().total());
        assertEquals(3, r.instances().items().size());
        assertEquals(Addresses.hex(chainHead()), r.instances().items().get(0).addr(), "head of the chain retains the whole chain");
        var e = assertThrows(hd.engine.api.EngineException.class, () ->
                InstancesView.list(graph, "no.such.Clazz", InstancesView.Sort.SHALLOW, Limits.defaults(), Redaction.ON, Cancel.none()));
        assertEquals(hd.engine.api.EngineException.Code.INVALID_ARGUMENT, e.code());
    }

    @Test
    void threads() {
        var r = ThreadsView.list(leaks, null, false, Limits.defaults(), Redaction.ON, Cancel.none());
        assertTrue(r.stackDataAvailable());
        var first = r.threads().items().get(0);
        assertEquals("local-holder", first.name());
        assertTrue(first.localsRetained().bytes() >= 2 * 1024 * 1024);
        assertTrue(first.localsRetained().bytes() < 3 * 1024 * 1024, "the 2 MiB local must not be counted twice: " + first.localsRetained());
        assertEquals("WAITING", first.state());
        assertEquals(Boolean.TRUE, first.daemon());
        var pool = r.stackGroups().stream().filter(g -> g.threads().stream().anyMatch(n -> n.startsWith("leak-pool-"))).findFirst().orElseThrow();
        assertEquals(4, pool.count());

        var one = ThreadsView.list(leaks, "local-holder", true, Limits.defaults(), Redaction.ON, Cancel.none());
        assertEquals(1, one.threads().items().size());
        var frame = one.threads().items().get(0).frames().stream()
                .filter(f -> f.method().endsWith("LeaksScenario.holdLocal")).findFirst().orElseThrow();
        assertTrue(frame.locals().stream().anyMatch(l -> l.cls().equals("byte[]") && l.retained().bytes() >= 2 * 1024 * 1024));
    }

    @Test
    void oql() {
        var objs = OqlView.run(graph, "SELECT * FROM hd.fixtures.model.ChainNode", Limits.first(4), Redaction.ON, Cancel.none());
        assertEquals(10, objs.rows().total());
        assertEquals(4, objs.rows().items().size());
        assertInstanceOf(ObjRef.class, objs.rows().items().get(0).get(0));

        var strings = OqlView.run(graph, "SELECT toString(s.name) FROM hd.fixtures.model.DiamondNode s", Limits.defaults(), Redaction.ON, Cancel.none());
        assertTrue(strings.rows().items().stream().allMatch(row -> row.get(0) instanceof Redaction.Redacted));
        var shown = OqlView.run(graph, "SELECT toString(s.name) FROM hd.fixtures.model.DiamondNode s", Limits.defaults(), Redaction.OFF, Cancel.none());
        assertTrue(shown.rows().items().stream().anyMatch(row -> "bottom".equals(row.get(0))));
    }

    @Test
    void info() {
        var i = InfoView.summary(graph, Redaction.ON, Cancel.none());
        assertEquals("mat", i.engine());
        assertEquals(5, i.topRetainers().size());
        assertEquals(5, i.topClasses().size());
        assertTrue(i.threads() >= 3);
    }

    // ---- detectors

    private Detectors.Report detect(Heap heap, List<String> only) {
        return Detectors.run(new DetectContext(heap, Cancel.none(), Progress.NONE), only);
    }

    private List<Finding> findings(Detectors.Report r, String id) {
        assertTrue(r.failures().isEmpty(), "detector failures: " + r.failures());
        return r.findings().stream().filter(f -> f.detector().equals(id)).toList();
    }

    @Test
    void detectorsFireOnLeaks() {
        var r = detect(leaks, List.of());
        assertEquals(List.of("big-retainers", "classloader", "collections", "direct-buffers", "threadlocal"), r.ran());

        var tl = findings(r, "threadlocal");
        assertEquals(1, tl.size(), "one finding for the LeakyValue group: " + tl);
        assertTrue(tl.get(0).title().contains("hd.fixtures.model.LeakyValue"));
        assertEquals(4, tl.get(0).evidence().count());

        var cl = findings(r, "classloader");
        assertEquals(1, cl.size(), cl.toString());
        assertTrue(cl.get(0).title().contains("hd.fixtures.LeaksScenario$LeakyLoader"));
        assertEquals(3, cl.get(0).evidence().count());

        var col = findings(r, "collections");
        assertTrue(col.stream().anyMatch(f -> f.title().contains("sparse java.util.HashMap")), col.toString());
        assertTrue(col.stream().anyMatch(f -> f.title().startsWith("Large java.util.ArrayList")), col.toString());

        var db = findings(r, "direct-buffers");
        assertEquals(1, db.size());
        // the fixture's 8 x 1 MiB, plus small JDK-internal direct buffers; the JDK's mapped module image is excluded
        assertTrue(db.get(0).evidence().count() >= 8, db.toString());
        assertTrue(db.get(0).evidence().size().bytes() >= 8L << 20);
        assertTrue(db.get(0).evidence().size().bytes() < 9L << 20, "mapped files must not be counted: " + db);
        assertTrue(db.get(0).explanation().contains("8 held by java.util.ArrayList"), db.get(0).explanation());

        var big = findings(r, "big-retainers");
        assertFalse(big.isEmpty());
        for (Finding f : r.findings()) {
            assertFalse(f.next().isEmpty());
            assertTrue(f.next().stream().allMatch(n -> n.contains("{session}")));
            assertFalse(f.evidence().addrs().isEmpty());
        }
    }

    @Test
    void dupStringsIsOptIn() {
        var r = detect(leaks, List.of("dup-strings"));
        var dup = findings(r, "dup-strings");
        assertEquals(1, dup.size());
        assertTrue(dup.get(0).evidence().count() >= 5000);
        assertFalse(dup.get(0).explanation().contains("duplicate-value"), "content must never be reported");
    }

    @Test
    void detectorsSilentOnGraph() {
        var r = detect(graph, List.of("classloader", "collections", "direct-buffers", "threadlocal", "dup-strings"));
        assertEquals(List.of(), r.findings(), "graph fixture has none of these problems");
    }

    @Test
    void unknownDetector() {
        var e = assertThrows(hd.engine.api.EngineException.class, () -> detect(graph, List.of("nope")));
        assertTrue(e.getMessage().contains("known"));
    }
}
