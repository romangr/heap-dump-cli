package hd.daemon;

import hd.analysis.Heap;
import hd.analysis.Limits;
import hd.analysis.Page;
import hd.analysis.Redaction;
import hd.analysis.Size;
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
import hd.engine.api.Snapshot;
import hd.engine.api.capability.FastGcRootPaths.RefKind;
import hd.hprof.Triage;
import hd.hprof.TriageResult;
import hd.protocol.ErrorCode;
import hd.protocol.Request;
import hd.protocol.Response;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Command dispatch: every request goes through {@link #handle}. */
final class Commands {

    /** Default time a request waits for its job before returning a job id (the CLI usually sends its own). */
    static final long DEFAULT_WAIT_MS = 8 * 60 * 1000;

    private final SessionManager sessions;
    private final Jobs jobs;
    private final Supplier<Map<String, Object>> status;
    private final Runnable shutdown;

    Commands(SessionManager sessions, Jobs jobs, Supplier<Map<String, Object>> status, Runnable shutdown) {
        this.sessions = sessions;
        this.jobs = jobs;
        this.status = status;
        this.shutdown = shutdown;
    }

    Response handle(Request r) {
        try {
            return switch (r.cmd()) {
                case "status" -> Response.ok(null, status.get(), null, null);
                case "shutdown" -> {
                    shutdown.run();
                    yield Response.ok(null, Map.of("stopping", true), null, null);
                }
                case "open" -> open(r);
                case "wait" -> waitFor(r);
                case "sessions" -> Response.ok(null, sessions.list().stream().map(Session::describe).toList(), null, null);
                case "close" -> {
                    sessions.get(r.str("session"));
                    sessions.close(r.str("session"));
                    yield Response.ok(null, Map.of("closed", r.str("session")), null, null);
                }
                case "triage" -> triage(r);
                case "info", "histogram", "dominators", "object", "refs", "path-to-roots", "instances", "threads", "detect", "oql" -> query(r);
                default -> throw new HdException(ErrorCode.INVALID_ARGUMENT, "unknown command " + r.cmd());
            };
        } catch (Throwable t) {
            return Errors.toResponse(t);
        }
    }

    // ---- lifecycle commands

    private Response open(Request r) {
        Session s = sessions.open(Path.of(require(r, "dump")), r.bool("force"));
        long wait = r.integer("wait_s", 0) * 1000L;
        waitWhileIndexing(s, wait);
        return sessionResponse(s);
    }

    private Response waitFor(Request r) {
        String id = require(r, "id");
        long wait = r.integer("timeout_s", (int) (DEFAULT_WAIT_MS / 1000)) * 1000L;
        if (id.startsWith("j:")) {
            return jobs.awaitOrPending(jobs.get(id), wait);
        }
        Session s = sessions.get(id);
        waitWhileIndexing(s, wait);
        return sessionResponse(s);
    }

    private static void waitWhileIndexing(Session s, long waitMillis) {
        long deadline = System.currentTimeMillis() + waitMillis;
        while (s.state() == Session.State.INDEXING && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(Math.min(250, Math.max(1, deadline - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private Response sessionResponse(Session s) {
        Map<String, Object> d = s.describe();
        return switch (s.state()) {
            case INDEXING -> Response.ok(sessions.engine().id(), d, null, List.of("hd wait " + s.id()));
            case READY -> Response.ok(sessions.engine().id(), d, null, List.of("hd info " + s.id(), "hd detect " + s.id()));
            case FAILED -> Response.error(ErrorCode.valueOf(String.valueOf(d.get("error_code"))),
                    "opening " + s.dump() + " failed: " + d.get("error"), d);
            case CLOSED -> Response.error(ErrorCode.SESSION_NOT_FOUND, "session " + s.id() + " was closed", d);
        };
    }

    // ---- triage

    private Response triage(Request r) {
        Path dump = Path.of(require(r, "dump")).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(dump)) {
            throw new HdException(ErrorCode.FILE_NOT_FOUND, "no such file: " + dump);
        }
        Limits limits = limits(r);
        Job job = jobs.submit("triage " + dump.getFileName(), j -> {
            try {
                TriageResult t = Triage.run(dump, Triage.Options.defaults(), j::progress, j.cancel());
                return Response.ok("hprof-stream", TriageView.of(t, limits), pageInfo(t.classes().size(), limits),
                        List.of("hd open " + dump));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        return jobs.awaitOrPending(job, r.integer("wait_s", (int) (DEFAULT_WAIT_MS / 1000)) * 1000L);
    }

    // ---- queries on a ready session (run as jobs)

    private Response query(Request r) {
        Session s = sessions.ready(require(r, "session"));
        String engine = sessions.engine().id();
        Job job = jobs.submit(r.cmd() + " " + s.id(), j -> {
            s.enter();
            try {
                Heap heap = s.heap();
                if (heap == null) {
                    throw new HdException(ErrorCode.SESSION_NOT_FOUND, "session " + s.id() + " was closed");
                }
                return runQuery(r, s.id(), heap, engine, j);
            } finally {
                s.exit();
            }
        });
        return jobs.awaitOrPending(job, r.integer("wait_s", (int) (DEFAULT_WAIT_MS / 1000)) * 1000L);
    }

    private Response runQuery(Request r, String sid, Heap heap, String engine, Job job) {
        Redaction red = new Redaction(r.bool("show_values"));
        Limits limits = limits(r);
        Cancel cancel = job.cancel();
        switch (r.cmd()) {
            case "info" -> {
                var info = InfoView.summary(heap, red, cancel);
                List<String> hints = new ArrayList<>(List.of("hd detect " + sid, "hd dominators " + sid));
                if (!info.topRetainers().isEmpty()) hints.add("hd path-to-roots " + sid + " " + info.topRetainers().get(0).addr());
                return Response.ok(engine, info, null, hints);
            }
            case "histogram" -> {
                var by = enumArg(r, "by", HistogramView.By.class, HistogramView.By.CLASS);
                var sort = enumArg(r, "sort", HistogramView.Sort.class, HistogramView.Sort.SHALLOW);
                var res = HistogramView.compute(heap, by, pattern(r.str("filter")), sort, limits, job::progress, cancel);
                List<String> hints = new ArrayList<>();
                if (by == HistogramView.By.CLASS && !res.rows().items().isEmpty()) {
                    hints.add("hd instances " + sid + " " + res.rows().items().get(0).name() + " --sort retained");
                }
                if (sort != HistogramView.Sort.RETAINED) hints.add("hd histogram " + sid + " --sort retained   (slower: visits every object)");
                return Response.ok(engine, res, pageInfo(res.rows()), hints);
            }
            case "dominators" -> {
                long root = r.str("root") == null ? Snapshot.ROOT : Addresses.parse(r.str("root"));
                boolean group = "class".equals(r.str("group_by"));
                var res = DominatorView.children(heap, root, group, limits, red, cancel);
                List<String> hints = new ArrayList<>();
                if (res.rows() != null && !res.rows().items().isEmpty()) {
                    String top = res.rows().items().get(0).addr();
                    hints.add("hd dominators " + sid + " --root " + top);
                    hints.add("hd path-to-roots " + sid + " " + top);
                } else if (res.groups() != null && !res.groups().items().isEmpty()) {
                    hints.add("hd object " + sid + " " + res.groups().items().get(0).largestAddr());
                }
                return Response.ok(engine, res, pageInfo(res.rows() != null ? res.rows() : res.groups()), hints);
            }
            case "object" -> {
                long a = Addresses.parse(require(r, "addr"));
                var res = ObjectInspector.inspect(heap, a, !r.bool("no_fields"), limits, red);
                return Response.ok(engine, res, res.elements() == null ? null : pageInfo(res.elements()),
                        List.of("hd path-to-roots " + sid + " " + Addresses.hex(a), "hd refs " + sid + " " + Addresses.hex(a) + " --in",
                                "hd dominators " + sid + " --root " + Addresses.hex(a)));
            }
            case "refs" -> {
                long a = Addresses.parse(require(r, "addr"));
                var dir = enumArg(r, "direction", RefsView.Direction.class, RefsView.Direction.OUT);
                var res = RefsView.refs(heap, a, dir, limits, red, cancel);
                return Response.ok(engine, res, pageInfo(res.refs()), List.of("hd path-to-roots " + sid + " " + Addresses.hex(a)));
            }
            case "path-to-roots" -> {
                long a = Addresses.parse(require(r, "addr"));
                Set<RefKind> exclude = EnumSet.noneOf(RefKind.class);
                List<String> ex = r.args().containsKey("exclude") ? r.list("exclude") : List.of("weak", "soft", "phantom");
                for (String e : ex) {
                    if (e.equalsIgnoreCase("none")) continue;
                    exclude.add(enumValue(e, RefKind.class, "exclude"));
                }
                var res = PathsView.toRoots(heap, a, exclude, r.integer("max_paths", 5), red, cancel);
                List<String> hints = new ArrayList<>();
                if (!res.paths().isEmpty()) {
                    var path = res.paths().get(0).path();
                    if (path.size() > 1) hints.add("hd object " + sid + " " + path.get(1).obj().addr());
                }
                return Response.ok(engine, res, null, hints);
            }
            case "instances" -> {
                var sort = enumArg(r, "sort", InstancesView.Sort.class, InstancesView.Sort.RETAINED);
                var res = InstancesView.list(heap, require(r, "class"), sort, limits, red, cancel);
                List<String> hints = new ArrayList<>();
                if (!res.instances().items().isEmpty()) {
                    String top = res.instances().items().get(0).addr();
                    hints.add("hd path-to-roots " + sid + " " + top);
                    hints.add("hd object " + sid + " " + top);
                }
                return Response.ok(engine, res, pageInfo(res.instances()), hints);
            }
            case "threads" -> {
                var res = ThreadsView.list(heap, r.str("thread"), r.bool("with_locals"), limits, red, cancel);
                List<String> hints = new ArrayList<>();
                if (r.str("thread") == null && !res.threads().items().isEmpty()) {
                    hints.add("hd threads " + sid + " --thread \"" + res.threads().items().get(0).name() + "\" --with-locals");
                }
                return Response.ok(engine, res, pageInfo(res.threads()), hints);
            }
            case "detect" -> {
                var report = Detectors.run(new DetectContext(heap, cancel, job::progress), r.list("only"));
                List<Finding> findings = report.findings().stream().map(f -> new Finding(f.detector(), f.severity(),
                        f.title(), f.explanation(), f.evidence(),
                        f.next().stream().map(n -> n.replace("{session}", sid)).toList())).toList();
                List<String> hints = new ArrayList<>();
                if (!findings.isEmpty()) hints.addAll(findings.get(0).next());
                if (!report.ran().contains("dup-strings")) hints.add("hd detect " + sid + " --only dup-strings   (opt-in: reads every string)");
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("ran", report.ran());
                data.put("findings", findings);
                if (!report.failures().isEmpty()) data.put("failures", report.failures());
                return Response.ok(engine, data, null, hints);
            }
            case "oql" -> {
                var res = OqlView.run(heap, require(r, "query"), limits, red, cancel);
                return Response.ok(engine, res, pageInfo(res.rows()), null);
            }
            default -> throw new HdException(ErrorCode.INVALID_ARGUMENT, "unknown command " + r.cmd());
        }
    }

    // ---- argument helpers

    static String require(Request r, String key) {
        String v = r.str(key);
        if (v == null || v.isBlank()) {
            throw new HdException(ErrorCode.INVALID_ARGUMENT, "missing argument: " + key);
        }
        return v;
    }

    static Limits limits(Request r) {
        int limit = r.integer("limit", Limits.DEFAULT_LIMIT);
        int offset = r.integer("offset", 0);
        if (limit < 1 || offset < 0) throw new HdException(ErrorCode.INVALID_ARGUMENT, "limit must be >= 1 and offset >= 0");
        return new Limits(offset, limit);
    }

    static Pattern pattern(String regex) {
        if (regex == null) return null;
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new HdException(ErrorCode.INVALID_ARGUMENT, "invalid --filter regex: " + e.getDescription());
        }
    }

    static <E extends Enum<E>> E enumArg(Request r, String key, Class<E> type, E def) {
        String v = r.str(key);
        return v == null ? def : enumValue(v, type, key);
    }

    static <E extends Enum<E>> E enumValue(String v, Class<E> type, String key) {
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException e) {
            List<String> allowed = java.util.Arrays.stream(type.getEnumConstants()).map(c -> c.name().toLowerCase()).toList();
            throw new HdException(ErrorCode.INVALID_ARGUMENT, "invalid " + key + " '" + v + "', expected one of " + allowed);
        }
    }

    static Response.PageInfo pageInfo(Page<?> p) {
        return p == null ? null : new Response.PageInfo(p.limit(), p.offset(), p.total(), null);
    }

    static Response.PageInfo pageInfo(long total, Limits l) {
        return new Response.PageInfo(l.limit(), l.offset(), total, null);
    }

    /** Triage result shaped for output: hex addresses, readable sizes, paged class rows. */
    record TriageView(String file, Size fileSize, boolean gzip, int identifierSize, String created,
                      boolean compressedOopsAssumed, boolean compressedOopsGuessed, long objects, Size estimatedHeap,
                      Page<ClassRow> classes, List<ArrayRow> biggestArrays, TriageResult.Strings strings,
                      Map<String, Long> gcRoots, List<ThreadRow> threads, long elapsedMillis, String note) {

        record ThreadRow(String addr, List<TriageResult.FrameRow> frames) {}

        record ClassRow(String className, long count, Size estimatedShallow) {}

        record ArrayRow(String addr, String type, int length, Size estimatedShallow) {}

        static TriageView of(TriageResult t, Limits limits) {
            long heap = t.estimatedHeapBytes();
            List<ClassRow> rows = t.classes().stream().map(c -> new ClassRow(c.className(), c.count(), Size.of(c.estimatedBytes(), heap))).toList();
            List<ArrayRow> arrays = t.biggestArrays().stream()
                    .map(a -> new ArrayRow(Addresses.hex(a.address()), a.type(), a.length(), Size.of(a.estimatedBytes(), heap))).toList();
            return new TriageView(t.file().toString(), Size.of(t.fileBytes()), t.gzip(), t.identifierSize(),
                    t.timestampMillis() > 0 ? java.time.Instant.ofEpochMilli(t.timestampMillis()).toString() : null,
                    t.compressedOops(), t.compressedOopsGuessed(), t.objects(), Size.of(heap), Page.of(rows, limits),
                    arrays, t.strings(), t.gcRoots(),
                    t.threads().stream().map(th -> new ThreadRow(Addresses.hex(th.address()), th.frames())).toList(),
                    t.elapsedMillis(),
                    "streaming overview: sizes are estimates, retained sizes need hd open; counts include unreachable objects");
        }
    }
}
