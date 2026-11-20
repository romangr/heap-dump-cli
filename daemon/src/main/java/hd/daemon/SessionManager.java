package hd.daemon;

import hd.engine.api.EngineException;
import hd.engine.api.HeapEngine;
import hd.engine.api.OpenOptions;
import hd.protocol.ErrorCode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/** Opens dumps asynchronously, tracks sessions, enforces the memory check and the loaded-snapshot limit. */
public final class SessionManager {

    private final DaemonConfig config;
    private final HeapEngine engine;
    private final ExecutorService workers;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    SessionManager(DaemonConfig config, ExecutorService workers) {
        this.config = config;
        this.workers = workers;
        this.engine = ServiceLoader.load(HeapEngine.class).stream().map(ServiceLoader.Provider::get)
                .filter(e -> e.id().equals(config.engine())).findFirst()
                .orElseThrow(() -> new IllegalStateException("engine '" + config.engine() + "' not found on the classpath"));
    }

    public HeapEngine engine() {
        return engine;
    }

    /** Stable id for a dump file: same path, size and modification time → same session. */
    static String sessionId(Path dump) throws IOException {
        String key = dump.toRealPath() + "|" + Files.size(dump) + "|" + Files.getLastModifiedTime(dump).toMillis();
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return "s:" + HexFormat.of().formatHex(h, 0, 3);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Where this dump's indexes go: configured dir, next to the dump if writable, else under ~/.hd/index. */
    Path indexDir(Path dump, String id) {
        String sub = id.substring(2);
        if (config.indexDir() != null) return config.indexDir().resolve(sub);
        if (Files.isWritable(dump.getParent())) return null;
        return config.paths().indexRoot().resolve(sub);
    }

    public synchronized Session open(Path dumpArg, boolean force) {
        Path dump = dumpArg.toAbsolutePath().normalize();
        if (!Files.isRegularFile(dump)) {
            throw new HdException(ErrorCode.FILE_NOT_FOUND, "no such file: " + dump);
        }
        String id;
        try {
            id = sessionId(dump);
        } catch (IOException e) {
            throw new HdException(ErrorCode.FILE_NOT_FOUND, "cannot read " + dump + ": " + e.getMessage());
        }
        Session existing = sessions.get(id);
        if (existing != null && existing.state() != Session.State.FAILED && existing.state() != Session.State.CLOSED) {
            existing.touch();
            return existing;
        }

        Path indexDir = indexDir(dump, id);
        evictFor(id);
        MemoryEstimate est;
        try {
            est = MemoryEstimate.of(dump, engine.hasIndex(dump, new OpenOptions(indexDir, false)), config, availableMemory());
        } catch (IOException e) {
            throw new HdException(ErrorCode.FILE_NOT_FOUND, "cannot read " + dump + ": " + e.getMessage());
        }
        if (!force && !est.fits()) {
            Map<String, Object> details = new java.util.LinkedHashMap<>(est.describe());
            details.put("suggestions", List.of(
                    "hd daemon restart -Xmx<size>   (if the machine has enough RAM; the estimate is conservative)",
                    "hd triage " + dump + "   (streaming overview, constant memory)",
                    "hd open " + dump + " --force   (try anyway; the daemon may run out of memory)"));
            throw new HdException(ErrorCode.INSUFFICIENT_MEMORY, String.format(
                    "opening %s needs an estimated %s but the daemon has %s available",
                    dump.getFileName(), hd.analysis.Size.human(est.requiredBytes()), hd.analysis.Size.human(est.availableBytes())), details);
        }

        if (!est.reopen()) {
            checkDisk(dump, indexDir, est);
        }

        Session s = new Session(id, dump);
        sessions.put(id, s);
        workers.submit(() -> {
            try {
                var snapshot = engine.open(dump, new OpenOptions(indexDir, false), s::progress, s.cancel());
                if (s.state() == Session.State.CLOSED) {
                    snapshot.close();
                } else {
                    s.ready(snapshot);
                }
            } catch (EngineException e) {
                s.failed(Errors.code(e).name(), e.getMessage());
            } catch (OutOfMemoryError e) {
                s.failed(ErrorCode.ENGINE_OOM.name(), "out of memory while indexing; increase the daemon -Xmx");
            } catch (RuntimeException e) {
                s.failed(ErrorCode.INTERNAL.name(), e.toString());
            }
        });
        return s;
    }

    /** Index files need roughly 1.4x the dump: fail early instead of running out of disk mid-indexing. */
    private void checkDisk(Path dump, Path indexDir, MemoryEstimate est) {
        Path dir = indexDir != null ? indexDir : dump.getParent();
        try {
            Path existing = dir;
            while (existing != null && !Files.exists(existing)) existing = existing.getParent();
            long free = Files.getFileStore(existing == null ? dump.getParent() : existing).getUsableSpace();
            long need = (long) (est.uncompressedBytes() * config.indexDiskRatio());
            if (free < need) {
                throw new HdException(ErrorCode.INSUFFICIENT_DISK, String.format(
                        "indexing %s needs about %s of free disk in %s, only %s is free", dump.getFileName(),
                        hd.analysis.Size.human(need), dir, hd.analysis.Size.human(free)),
                        Map.of("index_dir", dir.toString(), "required", hd.analysis.Size.of(need), "free", hd.analysis.Size.of(free),
                                "suggestions", List.of("set index.dir in ~/.hd/config.properties to a larger disk",
                                        "hd triage " + dump + "   (needs no index)")));
            }
        } catch (IOException e) {
            // cannot determine: let indexing try
        }
    }

    /** Memory the daemon can still use for a new snapshot. */
    static long availableMemory() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        return rt.maxMemory() - used;
    }

    /** Closes least recently used idle sessions so a new one fits within maxSessions. */
    private void evictFor(String newId) {
        List<Session> loaded = new ArrayList<>(sessions.values().stream()
                .filter(s -> !s.id().equals(newId) && (s.state() == Session.State.READY || s.state() == Session.State.INDEXING))
                .toList());
        loaded.sort(Comparator.comparingLong(Session::lastUsed));
        while (loaded.size() >= config.maxSessions()) {
            Session victim = loaded.stream().filter(s -> !s.isBusy()).findFirst().orElse(null);
            if (victim == null) break;
            close(victim.id());
            loaded.remove(victim);
        }
        System.gc(); // give the freed snapshot memory back before measuring what is available
    }

    public Session get(String id) {
        Session s = sessions.get(id);
        if (s == null || s.state() == Session.State.CLOSED) {
            throw new HdException(ErrorCode.SESSION_NOT_FOUND, "no session " + id + " (open the dump with hd open <dump>)");
        }
        return s;
    }

    /** The session, which must be READY; otherwise SESSION_NOT_READY with progress details. */
    public Session ready(String id) {
        Session s = get(id);
        if (s.state() == Session.State.INDEXING) {
            throw new HdException(ErrorCode.SESSION_NOT_READY, "session " + id + " is still indexing", s.describe());
        }
        if (s.state() == Session.State.FAILED) {
            throw new HdException(ErrorCode.valueOf(s.describe().get("error_code").toString()),
                    "opening failed: " + s.describe().get("error"), s.describe());
        }
        return s;
    }

    public synchronized void close(String id) {
        Session s = sessions.remove(id);
        if (s != null) s.close();
    }

    public List<Session> list() {
        return sessions.values().stream().filter(s -> s.state() != Session.State.CLOSED)
                .sorted(Comparator.comparing(Session::id)).toList();
    }

    boolean anyIndexing() {
        return sessions.values().stream().anyMatch(s -> s.state() == Session.State.INDEXING);
    }

    void closeAll() {
        sessions.keySet().forEach(this::close);
    }
}
