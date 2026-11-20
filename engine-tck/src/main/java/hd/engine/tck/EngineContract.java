package hd.engine.tck;

import hd.engine.api.Addresses;
import hd.engine.api.Cancel;
import hd.engine.api.ClassView;
import hd.engine.api.EngineException;
import hd.engine.api.FieldType;
import hd.engine.api.GcRoot;
import hd.engine.api.HeapEngine;
import hd.engine.api.ObjectKind;
import hd.engine.api.ObjectView;
import hd.engine.api.OpenOptions;
import hd.engine.api.Progress;
import hd.engine.api.Snapshot;
import hd.engine.api.ThreadView;
import hd.engine.api.capability.FastGcRootPaths;
import hd.engine.api.capability.FastHistogram;
import hd.engine.api.capability.Oql;
import hd.engine.api.capability.RetainedSetSize;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The engine contract. Every {@link HeapEngine} must pass it; extend this class in the engine's tests.
 *
 * Assertions are structural (graph shape, dominator relationships, invariants) rather than exact byte counts, so the
 * suite is independent of JVM object layout. Fixture shapes are documented in hd.fixtures.GraphScenario and
 * hd.fixtures.LeaksScenario.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class EngineContract {

    protected abstract HeapEngine engine();

    @TempDir
    Path indexDir;

    protected Snapshot graph;
    protected Snapshot leaks;

    @BeforeAll
    void openFixtures() {
        graph = engine().open(Fixtures.dump("graph"), new OpenOptions(indexDir, false), Progress.NONE, Cancel.none());
        leaks = engine().open(Fixtures.dump("leaks"), new OpenOptions(indexDir, false), Progress.NONE, Cancel.none());
    }

    @AfterAll
    void closeFixtures() {
        if (graph != null) graph.close();
        if (leaks != null) leaks.close();
    }

    // ---- helpers

    protected ClassView onlyClass(Snapshot s, String name) {
        List<ClassView> found = s.classesByName(name);
        assertEquals(1, found.size(), "expected exactly one class " + name);
        return found.get(0);
    }

    protected long onlyInstance(Snapshot s, String className) {
        long[] all = s.instancesOf(onlyClass(s, className).address()).toArray();
        assertEquals(1, all.length, "expected one instance of " + className);
        return all[0];
    }

    /** Reads a java.lang.String's content (JDK 9+ compact strings, or char[] on JDK 8). */
    protected String string(Snapshot s, long address) {
        if (address == Addresses.NULL) return null;
        ObjectView str = s.object(address);
        ObjectView value = s.object(str.refField("value"));
        Object raw = value.readPrimitives(0, value.length());
        if (raw instanceof char[] chars) return new String(chars);
        byte[] bytes = (byte[]) raw;
        byte coder = str.field("coder").map(f -> (Byte) f.value()).orElse((byte) 0);
        return coder == 0 ? new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                : new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE);
    }

    protected Map<String, Long> diamondNodesByName() {
        ClassView c = onlyClass(graph, "hd.fixtures.model.DiamondNode");
        Map<String, Long> byName = new HashMap<>();
        graph.instancesOf(c.address()).forEach(a -> byName.put(string(graph, graph.object(a).refField("name")), a));
        return byName;
    }

    // =====================================================================================================

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Basics {
        @Test
        void info() {
            var i = graph.info();
            assertTrue(i.objectCount() > 1000, "objectCount");
            assertTrue(i.classCount() > 100, "classCount");
            assertTrue(i.usedHeap() > 1 << 20, "usedHeap includes the 1 MiB payload");
            assertTrue(i.identifierSize() == 4 || i.identifierSize() == 8);
            assertEquals(engine().id(), i.engine());
        }

        @Test
        void unknownAddressIsObjectNotFound() {
            var e = assertThrows(EngineException.class, () -> graph.object(0x10L));
            assertEquals(EngineException.Code.OBJECT_NOT_FOUND, e.code());
        }

        @Test
        void classAndInstances() {
            ClassView root = onlyClass(graph, "hd.fixtures.model.FixtureRoot");
            assertFalse(root.array());
            assertTrue(root.instanceSize() > 0);
            assertEquals(1, root.instanceCount());
            assertEquals(Set.of("chain", "diamond", "cycle", "big"),
                    root.fields().stream().map(ClassView.FieldDecl::name).collect(Collectors.toSet()));
            ClassView object = graph.clazz(root.superclassAddress());
            assertEquals("java.lang.Object", object.name());

            ClassView nodes = onlyClass(graph, "hd.fixtures.model.ChainNode");
            assertEquals(10, nodes.instanceCount());
            assertEquals(10, graph.instancesOf(nodes.address()).count());
        }

        @Test
        void classesStreamContainsFixtureClasses() {
            Set<String> names = graph.classes().map(ClassView::name).collect(Collectors.toSet());
            assertTrue(names.containsAll(Set.of("hd.fixtures.model.FixtureRoot", "java.lang.String", "byte[]")));
        }

        @Test
        void classObjectViewHasStaticFields() {
            ClassView root = onlyClass(graph, "hd.fixtures.model.FixtureRoot");
            ObjectView c = graph.object(root.address());
            assertEquals(ObjectKind.CLASS, c.kind());
            assertEquals(onlyInstance(graph, "hd.fixtures.model.FixtureRoot"), c.refField("INSTANCE"));
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Fields {
        @Test
        void walkChainThroughFields() {
            long rootObj = onlyInstance(graph, "hd.fixtures.model.FixtureRoot");
            ObjectView root = graph.object(rootObj);
            assertEquals(ObjectKind.INSTANCE, root.kind());
            assertEquals("hd.fixtures.model.FixtureRoot", root.className());

            List<Integer> indexes = new ArrayList<>();
            long node = root.refField("chain");
            while (node != Addresses.NULL) {
                ObjectView n = graph.object(node);
                assertEquals("hd.fixtures.model.ChainNode", n.className());
                var index = n.field("index").orElseThrow();
                assertEquals(FieldType.INT, index.type());
                indexes.add((Integer) index.value());
                node = n.refField("next");
            }
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9), indexes);
        }

        @Test
        void primitiveArray() {
            ObjectView root = graph.object(onlyInstance(graph, "hd.fixtures.model.FixtureRoot"));
            ObjectView payload = graph.object(root.refField("big"));
            ObjectView data = graph.object(payload.refField("data"));
            assertEquals(ObjectKind.PRIMITIVE_ARRAY, data.kind());
            assertEquals("byte[]", data.className());
            assertEquals(FieldType.BYTE, data.elementType());
            assertEquals(1 << 20, data.length());
            assertTrue(data.shallowSize() >= 1 << 20);
            assertArrayEquals(new byte[16], (byte[]) data.readPrimitives(0, 16));
            assertEquals(4, ((byte[]) data.readPrimitives((1 << 20) - 4, 100)).length, "read is clamped to the end");
        }

        @Test
        void objectArray() {
            // LeaksScenario.HOLDER is an ArrayList whose elementData holds the scenario objects
            ClassView scenario = onlyClass(leaks, "hd.fixtures.LeaksScenario");
            ObjectView holder = leaks.object(leaks.object(scenario.address()).refField("HOLDER"));
            assertEquals("java.util.ArrayList", holder.className());
            ObjectView elements = leaks.object(holder.refField("elementData"));
            assertEquals(ObjectKind.OBJECT_ARRAY, elements.kind());
            int size = (Integer) holder.field("size").orElseThrow().value();
            long[] refs = elements.readReferences(0, size);
            assertEquals(size, refs.length);
            for (long r : refs) {
                assertNotEquals(Addresses.NULL, r);
                assertNotNull(leaks.object(r));
            }
        }

        @Test
        void stringDecoding() {
            assertEquals(Set.of("top", "left", "right", "bottom", "cycle-a", "cycle-b", "cycle-c"), diamondNodesByName().keySet());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Graph {
        @Test
        void outboundAndInbound() {
            Map<String, Long> n = diamondNodesByName();
            Set<Long> out = graph.outbound(n.get("top")).boxed().collect(Collectors.toSet());
            assertTrue(out.containsAll(Set.of(n.get("left"), n.get("right"))));
            assertTrue(out.contains(graph.object(n.get("top")).classAddress()), "outbound includes the class");

            Set<Long> in = graph.inbound(n.get("bottom")).boxed().collect(Collectors.toSet());
            assertTrue(in.containsAll(Set.of(n.get("left"), n.get("right"))));
        }

        @Test
        void classOfMatchesObjectView() {
            Map<String, Long> n = diamondNodesByName();
            for (long a : n.values()) {
                assertEquals(graph.object(a).classAddress(), graph.classOf(a));
            }
        }

        @Test
        void shallowSizesMatchObjectView() {
            Map<String, Long> n = diamondNodesByName();
            long a = n.get("top");
            assertEquals(graph.object(a).shallowSize(), graph.shallowSize(a));
            assertTrue(graph.shallowSize(a) > 0);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Dominators {
        @Test
        void diamond() {
            Map<String, Long> n = diamondNodesByName();
            long top = n.get("top");
            assertEquals(top, graph.immediateDominator(n.get("left")));
            assertEquals(top, graph.immediateDominator(n.get("right")));
            assertEquals(top, graph.immediateDominator(n.get("bottom")), "bottom is reachable via left and right, so top dominates it");
            Set<Long> dominated = graph.dominated(top).boxed().collect(Collectors.toSet());
            assertTrue(dominated.containsAll(Set.of(n.get("left"), n.get("right"), n.get("bottom"))));
        }

        @Test
        void cycle() {
            Map<String, Long> n = diamondNodesByName();
            assertEquals(n.get("cycle-a"), graph.immediateDominator(n.get("cycle-b")));
            assertEquals(n.get("cycle-b"), graph.immediateDominator(n.get("cycle-c")));
        }

        @Test
        void retainedSizeInvariant() {
            // retained(x) == shallow(x) + sum(retained(children in dominator tree))
            Map<String, Long> n = diamondNodesByName();
            List<Long> samples = new ArrayList<>(n.values());
            samples.add(onlyInstance(graph, "hd.fixtures.model.FixtureRoot"));
            for (long x : samples) {
                long children = graph.dominated(x).map(graph::retainedSize).sum();
                assertEquals(graph.retainedSize(x), graph.shallowSize(x) + children, "invariant for " + Addresses.hex(x));
            }
            assertTrue(graph.retainedSize(n.get("bottom")) >= 4096, "bottom retains its 4 KiB payload");
            assertTrue(graph.retainedSize(onlyInstance(graph, "hd.fixtures.model.FixtureRoot")) >= (1 << 20) + 10 * 1000);
        }

        @Test
        void rootLevel() {
            long[] top = graph.dominated(Snapshot.ROOT).limit(50).toArray();
            assertTrue(top.length > 0);
            for (long a : top) {
                assertEquals(Snapshot.ROOT, graph.immediateDominator(a));
            }
        }

        @Test
        void threadLocalValuesAreDominatedByTheirThread() {
            ClassView leaky = onlyClass(leaks, "hd.fixtures.model.LeakyValue");
            long[] values = leaks.instancesOf(leaky.address()).toArray();
            assertEquals(4, values.length);
            for (long v : values) {
                boolean underThread = false;
                for (long d = leaks.immediateDominator(v); d != Snapshot.ROOT; d = leaks.immediateDominator(d)) {
                    if (leaks.object(d).className().equals("java.lang.Thread")) {
                        underThread = true;
                        break;
                    }
                }
                assertTrue(underThread, "LeakyValue " + Addresses.hex(v) + " should be retained by a pool thread");
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RootsAndThreads {
        @Test
        void gcRoots() {
            List<GcRoot> roots = graph.gcRoots().toList();
            assertTrue(roots.size() > 10);
            assertTrue(roots.stream().anyMatch(r -> r.types().contains(GcRoot.Type.SYSTEM_CLASS)));
            assertTrue(roots.stream().anyMatch(r -> r.types().contains(GcRoot.Type.THREAD_OBJECT)));
            GcRoot any = roots.get(0);
            assertEquals(any.types(), graph.rootTypes(any.address()));
        }

        @Test
        void nonRootHasNoRootTypes() {
            assertEquals(List.of(), graph.rootTypes(diamondNodesByName().get("bottom")));
        }

        @Test
        void threadStackWithLocals() {
            ThreadView holder = leaks.threads()
                    .filter(t -> t.frames().stream().anyMatch(f -> f.method().endsWith("LeaksScenario.holdLocal")))
                    .findFirst().orElseThrow(() -> new AssertionError("thread in holdLocal not found"));
            assertEquals("java.lang.Thread", leaks.object(holder.address()).className());
            boolean bigLocal = holder.frames().stream()
                    .filter(f -> f.method().endsWith("LeaksScenario.holdLocal"))
                    .flatMapToLong(f -> java.util.Arrays.stream(f.locals()))
                    .mapToObj(leaks::object)
                    .anyMatch(o -> o.className().equals("byte[]") && o.length() == 2 * 1024 * 1024);
            assertTrue(bigLocal, "holdLocal frame must reference the 2 MiB byte[] local");
            assertNotNull(holder.frames().get(0).method());
        }

        @Test
        void classLoaderCopies() {
            List<ClassView> copies = leaks.classesByName("hd.fixtures.model.PluginComponent");
            assertEquals(3, copies.size());
            assertEquals(3, copies.stream().map(ClassView::loaderAddress).distinct().count());
            for (ClassView c : copies) {
                assertEquals("hd.fixtures.LeaksScenario$LeakyLoader", leaks.object(c.loaderAddress()).className());
            }
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Capabilities {
        @Test
        void histogram() {
            var h = graph.capability(FastHistogram.class);
            assumeTrue(h.isPresent());
            var rows = h.get().histogram(Cancel.none());
            var chain = rows.stream().filter(r -> r.className().equals("hd.fixtures.model.ChainNode")).findFirst().orElseThrow();
            assertEquals(10, chain.count());
            assertEquals(graph.info().usedHeap(), rows.stream().mapToLong(FastHistogram.Row::shallow).sum());
        }

        @Test
        void gcRootPaths() {
            var p = graph.capability(FastGcRootPaths.class);
            assumeTrue(p.isPresent());
            long bottom = diamondNodesByName().get("bottom");
            List<long[]> paths = p.get().paths(bottom, Set.of(FastGcRootPaths.RefKind.WEAK), 5, Cancel.none());
            assertFalse(paths.isEmpty());
            for (long[] path : paths) {
                assertEquals(bottom, path[0]);
                assertFalse(graph.rootTypes(path[path.length - 1]).isEmpty(), "path must end at a GC root");
            }
            assertTrue(paths.size() >= 2, "bottom is reachable through left and through right");
        }

        @Test
        void retainedSetSize() {
            var r = graph.capability(RetainedSetSize.class);
            assumeTrue(r.isPresent());
            Map<String, Long> n = diamondNodesByName();
            long left = n.get("left"), right = n.get("right"), bottom = n.get("bottom");
            long together = r.get().retainedSize(new long[]{left, right}, Cancel.none());
            assertEquals(graph.shallowSize(left) + graph.shallowSize(right) + graph.retainedSize(bottom), together,
                    "removing both left and right frees bottom too");
        }

        @Test
        void oql() {
            var o = graph.capability(Oql.class);
            assumeTrue(o.isPresent());
            var result = o.get().query("SELECT * FROM hd.fixtures.model.ChainNode", 100, Cancel.none());
            assertEquals(10, result.totalRows());
            assertEquals(10, result.rows().size());
            assertTrue(result.rows().get(0).get(0) instanceof Oql.ObjectRef);

            var limited = o.get().query("SELECT * FROM hd.fixtures.model.ChainNode", 3, Cancel.none());
            assertEquals(3, limited.rows().size());
            assertEquals(10, limited.totalRows());

            var e = assertThrows(EngineException.class, () -> o.get().query("SELEKT nonsense", 10, Cancel.none()));
            assertEquals(EngineException.Code.INVALID_ARGUMENT, e.code());
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Lifecycle {
        @Test
        void concurrentReads() throws Exception {
            List<Long> classes = graph.classes().map(ClassView::address).toList();
            Map<Long, Long> sequential = new HashMap<>();
            for (long c : classes) {
                sequential.put(c, graph.instancesOf(c).map(graph::retainedSize).sum());
            }
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Map<Long, Long> parallel = new java.util.concurrent.ConcurrentHashMap<>();
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                int offset = t;
                Thread th = new Thread(() -> {
                    try {
                        for (int i = offset; i < classes.size(); i += 8) {
                            long c = classes.get(i);
                            parallel.put(c, graph.instancesOf(c).map(graph::retainedSize).sum());
                        }
                    } catch (Throwable e) {
                        failure.set(e);
                    }
                });
                threads.add(th);
                th.start();
            }
            for (Thread th : threads) th.join();
            if (failure.get() != null) throw new AssertionError("concurrent read failed", failure.get());
            assertEquals(sequential, parallel);
        }

        @Test
        void reopenUsesIndexes(@TempDir Path dir) throws Exception {
            Path dump = Fixtures.dump("graph");
            long first;
            try (Snapshot s = engine().open(dump, new OpenOptions(dir, false), Progress.NONE, Cancel.none())) {
                first = s.info().objectCount();
            }
            Set<Path> files = new HashSet<>();
            try (var list = Files.list(dir)) {
                list.forEach(files::add);
            }
            assertTrue(files.size() > 1, "index files are written into the index dir");
            long start = System.nanoTime();
            try (Snapshot s = engine().open(dump, new OpenOptions(dir, false), Progress.NONE, Cancel.none())) {
                assertEquals(first, s.info().objectCount());
            }
            assertTrue(System.nanoTime() - start < 5_000_000_000L);
        }

        @Test
        void progressIsReportedAndCancelWorks(@TempDir Path dir) {
            List<Double> fractions = new java.util.concurrent.CopyOnWriteArrayList<>();
            try (Snapshot s = engine().open(Fixtures.dump("graph"), new OpenOptions(dir, false),
                    (phase, f) -> fractions.add(f), Cancel.none())) {
                assertNotNull(s.info());
            }
            assertFalse(fractions.isEmpty(), "progress reported while indexing");

            Cancel cancel = new Cancel();
            cancel.cancel();
            var e = assertThrows(EngineException.class, () -> engine().open(Fixtures.dump("leaks"),
                    new OpenOptions(dir.resolve("cancelled"), false), Progress.NONE, cancel));
            assertEquals(EngineException.Code.CANCELLED, e.code());
        }

        @Test
        void missingFile(@TempDir Path dir) {
            var e = assertThrows(EngineException.class, () -> engine().open(dir.resolve("nope.hprof"),
                    OpenOptions.defaults(), Progress.NONE, Cancel.none()));
            assertEquals(EngineException.Code.IO, e.code());
        }

        @Test
        void garbageFileIsCorruptDump(@TempDir Path dir) throws Exception {
            Path bad = dir.resolve("bad.hprof");
            Files.write(bad, "JAVA PROFILE 1.0.2\0garbage garbage garbage".getBytes());
            var e = assertThrows(EngineException.class, () -> engine().open(bad, OpenOptions.defaults(), Progress.NONE, Cancel.none()));
            assertEquals(EngineException.Code.CORRUPT_DUMP, e.code());
        }
    }
}
