# heap-dump-cli (`hd`) — design and implementation plan

Date: 2026-11-20
Status: v1 implemented (steps 1–8); see "Implementation status" at the end

## Goal

A CLI Java heap dump analyzer comparable to Eclipse MAT, built **agent-first** so Claude can drive it
through a skill. v1 uses MAT's core as the analysis engine behind a strict interface, so the engine can be
replaced by our own core later without touching features, CLI or skill.

## Constraints and decisions

| Topic | Decision |
|---|---|
| Primary user | Agent first. JSON output by default, `--format table` for humans |
| Dump size | 32 GB+ must be supported → memory, index reuse, async operations drive the design |
| Topology | Claude and analyzer on the same host. Local Unix-socket daemon, no network/auth |
| Stack | Java 21, Gradle (wrapper), picocli |
| MAT source | Not on Maven Central. `scripts/install-mat.sh` downloads a pinned MAT version, verifies checksum, installs the plugin JARs into `~/.m2`; Gradle reads them via `mavenLocal()` |
| MAT license | EPL-2.0. Our code is separate work; ship EPL text + notice when distributing MAT JARs |
| Engine boundary | **Option A**: graph-level interface + optional capabilities. All features live above the boundary |
| Object IDs | Heap addresses (`long`, shown as `0x…`). Never engine-internal ids |
| v1 scope | Core navigation, triage, OQL, leak detectors, thread view |
| Deferred to v2 | Diff of two dumps, GraalVM native client, our own core |

## Architecture

### Modules

```
heap-dump-cli/
├── engine-api/      # interface boundary. Zero dependencies
├── engine-mat/      # MAT adapter. ONLY module allowed to import org.eclipse.mat.*
├── engine-tck/      # contract test suite every engine must pass
├── fixtures/        # program that builds known object graphs and dumps them (.hprof fixtures)
├── hprof-stream/    # own streaming HPROF parser → triage. No MAT
├── analysis/        # views, detectors, JdkLayouts, redaction, paging. Depends only on engine-api
├── daemon/          # session manager, Unix-socket server, job runner
├── cli/             # `hd` picocli client, auto-starts daemon
├── skill/           # SKILL.md for Claude
└── scripts/install-mat.sh
```

Boundary enforcement:
- Gradle: `analysis`, `daemon`, `cli` compile against `engine-api` only. `engine-mat` is on the daemon's
  runtime classpath and found via `ServiceLoader`.
- ArchUnit test: no class outside `engine-mat` may reference `org.eclipse.mat..`.
- `engine-tck` is the contract: any future engine must pass it.

### Engine interface (`engine-api`)

```java
interface HeapEngine {
  String id();
  Snapshot open(Path hprof, OpenOptions opts, Progress p, Cancel c);
}

interface Snapshot extends AutoCloseable {
  SnapshotInfo info();
  ObjectView object(long addr);
  ClassView clazz(long classAddr);
  Stream<ClassView> classes();
  LongStream instancesOf(long classAddr);
  LongStream outbound(long addr);
  LongStream inbound(long addr);
  long retainedSize(long addr);
  long immediateDominator(long addr);
  LongStream dominated(long addr);      // ROOT sentinel for the top level
  Stream<GcRoot> gcRoots();
  Stream<ThreadView> threads();         // frames + locals per frame
  <C extends Capability> Optional<C> capability(Class<C> type);
}
```

Optional capabilities: `Oql`, `FastHistogram`, `FastGcRootPaths` (with weak/soft/phantom exclusion),
`RetainedSetSize`. `analysis` provides fallbacks for all but `Oql` (fallback for `RetainedSetSize`
is labeled approximate).

Rules: slow operations take `Cancel` and report `Progress`; typed errors (`ObjectNotFound`,
`CapabilityUnsupported`, `EngineOutOfMemory`, `CorruptDump`); `Snapshot` must allow concurrent reads
(verified by TCK). String decoding, collection readers and redaction live in `analysis`, not engines.

### Processes and JVM options

Two processes:
- **Client JVM** — started by the Gradle-generated `bin/hd` launcher with a small fixed heap (`-Xmx64m`,
  overridable via `HD_CLIENT_OPTS`). Parses args with picocli, talks to the daemon, never does analysis.
- **Daemon JVM** — long-lived, does all heavy work (open, indexing, queries, triage). Started detached by
  the client via `ProcessBuilder` using the client's `java.home`, with `-XX:+ExitOnOutOfMemoryError`.

Daemon JVM options, highest priority first:
1. CLI `-X…` options (picocli `@Option(names="-X") List<String>`; verified: `-Xmx48g`, `-XX:+UseZGC`,
   `-Xss2m` all parse). Help text: "JVM option for the analysis daemon".
2. `HD_JAVA_OPTS` env var (any JVM options, incl. `-D`).
3. `~/.hd/config.properties` → `daemon.jvm-opts=…` (also idle timeout, index dir, default limits).
4. Automatic: fraction of physical RAM.

If the daemon is already running with a different `-Xmx` → `DAEMON_XMX_MISMATCH` + hint
`hd daemon restart -Xmx64g`. Never restart silently. Other differing JVM options → warning in `hints`.
`hd daemon status` reports effective `-Xmx` and its source.

### Daemon and sessions

- One daemon per user, socket `~/.hd/daemon.sock`, one JSON request/response per connection.
- Idle shutdown (default 30 min); `hd daemon start|stop|restart|status`.
- Session id derived from dump path + size + mtime (`s:3fa2`); reopening returns the same session.
- Indexes persisted next to the dump, or `~/.hd/index/<hash>/` if read-only. Version marker
  (engine + MAT version); stale/corrupt indexes are deleted and rebuilt.
- Default: one large snapshot loaded at a time, LRU close. `hd sessions` lists them.
- **Async open:** `hd open` returns immediately with state/progress/ETA. `hd wait <s> --timeout 8m`
  blocks up to the timeout (agent commands are capped at 10 min). Queries on a non-ready session →
  `SESSION_NOT_READY` with progress.
- **Memory check on open:** estimate from file size (ratio calibrated in step 8; `.hprof.gz` uses a
  typical compression ratio and is marked approximate), compared with the daemon's actual `-Xmx`.
  Fails early with `INSUFFICIENT_MEMORY` + estimate + suggestions (bigger `-Xmx`, or `hd triage`).
  `--force` skips the check; OOM then still yields a clean `ENGINE_OOM`.
- Triage also runs in the daemon as an async job (constant memory, may exceed command timeout).

### CLI commands (v1)

```
hd triage <dump>
hd open <dump> [-X…] [--force] | wait <s> [--timeout] | sessions | close <s>
hd info <s>
hd histogram <s> [--by class|package|loader] [--filter regex] [--sort retained|shallow|count]
hd dominators <s> [--root <addr>] [--group-by class]
hd object <s> <addr> [--fields] [--show-values]
hd refs <s> <addr> --in|--out
hd path-to-roots <s> <addr> [--exclude weak,soft,phantom] [--max-paths 5]
hd instances <s> <class-name> [--sort retained]
hd threads <s> [--with-locals] [--thread <name|addr>]
hd detect <s> [--only …] [--all]
hd oql <s> "<query>"
hd daemon start|stop|restart|status [-X…]
```

Response envelope:

```json
{ "ok": true, "engine": "mat", "data": {},
  "page": {"limit": 25, "offset": 0, "total": 18342, "next": "hd histogram s:3fa2 --offset 25"},
  "truncated": ["…"], "hints": ["…"] }
{ "ok": false, "error": {"code": "SESSION_NOT_READY", "message": "…", "details": {}} }
```

Agent-friendly rules: default limits (25 rows, depth 3, 5 paths); every row has a reusable `addr`;
sizes as bytes + human form + `pct` of heap; repeated GC-root paths grouped with `count` + one sample;
`hints` suggest the next command; exit codes map to error categories.

**Redaction on by default:** string / `char[]` / `byte[]` contents → `{"len":N,"redacted":true}`.
Primitives, class, field and thread names are shown. `--show-values` disables it per call.

### Detectors (`analysis`, ServiceLoader-registered)

| Detector | Default | Finds |
|---|---|---|
| `big-retainers` | yes | Dominator-tree entries above threshold (default 5% of heap) |
| `classloader` | yes | Duplicate, still-reachable class loaders (redeploy leaks) + root paths |
| `threadlocal` | yes | Large values in `ThreadLocalMap`s of pool threads |
| `collections` | yes | Huge or low-fill collections (HashMap, ArrayList, CHM, …) |
| `direct-buffers` | yes | `DirectByteBuffer` capacity totals grouped by holder |
| `dup-strings` | opt-in | Duplicate strings by content hash; never outputs content |

Finding shape: `{detector, severity, title, evidence:{addrs, retained}, explanation, next:[commands]}`.
JDK field layouts (String coder, HashMap table, ThreadLocalMap entries) isolated in `JdkLayouts`,
tested on JDK 11 and 21 fixtures.

### Thread view

Per thread: name, state, daemon flag, frames, retained size reachable from locals; sorted by that size.
`--with-locals` lists locals per frame. Identical stacks grouped. If the dump has no stack data → clear
note, not an empty list.

### Triage (`hprof-stream`)

Single sequential pass, large buffered reads, constant memory. Output: per-class count + shallow bytes
(retained explicitly unavailable), top-K biggest arrays with addresses, string totals (no content),
GC-root counts by type, threads with stack traces. Supports `.hprof` and `.hprof.gz`. Progress + ETA,
cancellable. Record reader designed for reuse by a future own core.

### MAT adapter (`engine-mat`)

- Main risk: running MAT without OSGi. Spike tries (a) manual parser/extension registration on a plain
  classpath; fallback (b) embedded Equinox inside the daemon. Contained in `engine-mat` either way.
- Address ↔ MAT id mapping internal (`mapAddressToId`); only addresses leave the adapter.
- Index directory: verify configurability in spike; fallback symlink the dump into `~/.hd/index/<hash>/`.
- Capabilities map to MAT queries. MAT version checked at load and written into index markers.

## Skill workflow (`skill/SKILL.md`)

1. `hd open <dump>`
   - fits → loop `hd wait` → `info` → `detect` → dig in (`dominators`, `path-to-roots`, `object`,
     `threads`) → summarize with addresses as evidence.
   - `INSUFFICIENT_MEMORY` → report estimate; offer `hd daemon restart -Xmx…` if RAM allows, or
     `hd triage` as fallback.
2. `hd triage` only as that fallback or when the user asks for a quick look.

Rules: never `--show-values` unless the user asks; respect paging and `hints`; never `--force` on own
initiative. Handle `SESSION_NOT_READY`, `DAEMON_XMX_MISMATCH`, `ENGINE_OOM`, `CAPABILITY_UNSUPPORTED`.

## Testing

1. `engine-tck` — contract suite on generated fixtures with checked-in expected values.
2. `analysis` unit tests — each detector: one firing fixture, one silent fixture.
3. `hprof-stream` — triage histogram cross-checked against MAT engine on the same fixtures.
4. CLI end-to-end — real `hd` against a test daemon: envelope, error codes, `-X` handling incl. mismatch.
5. Manual large-dump run (not CI) — triage throughput, index time, daemon memory → calibrates the
   memory estimate.

Fixtures generated on JDK 21 and JDK 11.

## Implementation steps

### 1. Spike: MAT on a plain classpath (go/no-go) — DONE, GO
- [x] `scripts/install-mat.sh`: pinned MAT 1.17.0 + SHA-512, writes the `~/.m2` layout directly (no Maven needed)
- [x] Minimal `fixtures` program: a few known object graphs, dump via `HotSpotDiagnosticMXBean`
- [x] Spike code: open fixture via MAT without OSGi; print histogram, one dominator level, one GC-root path
- [x] Reopen from persisted indexes; measure
- [x] Check whether index directory is configurable
- [x] Decision recorded: manual registration vs embedded Equinox

**Spike results (2026-11-20):** plain classpath works, no Equinox. OSGi touchpoints handled in one bootstrap class:
1. Non-OSGi extension registry (`RegistryFactory.createRegistry`) fed with each MAT JAR's `plugin.xml`;
   `%key Default` translations need a defaulting `ResourceBundle`.
2. Plugin singletons (`plugin`, `tracker`, parser `registry`) set by reflection, never `Plugin.start()`.
   `HprofPlugin` gets a proxy `Bundle` (preferences read its symbolic name).
3. `InternalPlatform.contentTracker` / `preferencesTracker` → `ServiceTracker` subclasses returning a no-types
   content-type manager (parsers match by extension) and a defaults-returning preferences service (with overrides).
4. `PlatformActivator.context` → proxy `BundleContext` whose `getProperty` reads system properties.

Measured: boot ~110 ms; 26 MB fixture first parse 730 ms, reopen in a fresh JVM 69 ms.
Index directory is not configurable (prefix = dump path); the symlink fallback works — all index files land next
to the link. Fixture dumps are generated by a cached Gradle task (`:fixtures:generateFixtures`), not checked in.

### 2. Project skeleton — DONE
- [x] Gradle 9.9.2 wrapper, multi-project build, Java 21 toolchain, version catalog, `mavenLocal()` restricted to `org.eclipse.mat`
- [x] `engine-api` types, capabilities, errors (+ `classOf`, `hasIndex` added during implementation)
- [x] ArchUnit boundary tests (`architecture` module; verified to fail on a planted violation)
- [x] EPL notice handling: `THIRD-PARTY-NOTICES.md` shipped in the distribution

### 3. MAT engine + TCK — DONE
- [x] `engine-tck`: 30 contract tests on generated fixtures (structural assertions, no layout-specific byte counts)
- [x] `engine-mat` implementing `Snapshot` + all 4 capabilities; passes the TCK
- [x] Index location (symlink into index dir), version marker, rebuild on mismatch/unknown builder

### 4. Triage (`hprof-stream`) — DONE
- [x] Streaming reader (`.hprof` + `.hprof.gz`), payloads skipped by seeking
- [x] Histogram, top-K arrays, string totals, root counts, thread stacks
- [x] Cross-checked against the MAT engine (exact for fixture classes; JDK classes ≥ MAT counts, avg size within 5%)

### 5. Daemon — DONE
- [x] Unix-socket server (virtual-thread handlers), single-instance lock, SIGHUP ignored, idle shutdown
- [x] Sessions (async open, `wait`, LRU, `max-sessions`), jobs
- [x] Memory estimate from file size (calibrated), `--force`; disk-space check (`INSUFFICIENT_DISK`, added)
- [x] Error code mapping, `ENGINE_OOM` detection (daemon log) after `-XX:+ExitOnOutOfMemoryError`

### 6. CLI — DONE
- [x] picocli commands, `-X` option (single-letter, attached value), env/config/auto precedence, detached daemon start
- [x] `DAEMON_XMX_MISMATCH`, `daemon status` with `xmx_source`
- [x] JSON envelope, `page.next` (shell-quoted), hints, `--format table`, exit codes; usage errors as JSON too
- [x] Redaction + `--show-values`
- [x] End-to-end tests against the installed launcher (9 tests, real daemon)

### 7. Analysis features — DONE
- [x] Views: info, histogram (by class/package/loader; retained = outermost instances via one dominator-tree pass),
      dominators (+ group by class), object, refs, path-to-roots (engine + BFS fallback, grouped shapes), instances, threads
- [x] `JdkLayouts` — tested on JDK 21 only (gap: JDK 11/17 layouts untested, no such JDK installed)
- [x] Detectors: big-retainers, classloader, collections, direct-buffers, threadlocal, dup-strings (opt-in)
- [x] OQL passthrough with redaction of string cells
- [x] Detector tests: all fire on `leaks`, none of the non-retainer detectors fire on `graph`

### 8. Skill + calibration — DONE
- [x] `skill/SKILL.md` (also shipped in the distribution)
- [x] Calibration on a 3.6 GB / 90M-object synthetic dump: triage 1.0 s; first index fails at -Xmx 0.56×, works at
      0.69× (87 s); reopen + queries at 0.14×; indexes 1.4× dump size → defaults memory.ratio 0.75, reopen 0.25,
      disk 1.5
- [ ] Try the skill end to end on a realistic leak dump (needs a real application dump)

## Implementation status and deviations from the design

- **Every query runs as a job** (not only open/triage): a query returns its result if it finishes within `--timeout`
  (default 8m), otherwise a job id for `hd wait`. Covers slow `detect` / `histogram --sort retained` on huge dumps.
- **`-X` instead of `--jvm-opt`**: picocli supports attached values only on single-letter options, so `-X` carries any
  `-X…`/`-XX:…` option exactly as `java` spells it.
- **Fixtures generated, not checked in** (cached Gradle task).
- **Detector heuristics refined by real data**: direct buffers count only cleaner-backed allocations (the JDK's
  134 MiB module image buffer and mapped files are excluded) and are grouped by first non-Reference holder;
  "large collection" requires ≥ 1000 elements; thread locals exclude the thread itself and nested locals.
- **Socket path**: falls back to `$TMPDIR/hd-<hash>.sock` when `$HD_HOME/daemon.sock` exceeds the ~104-byte limit.
- Open items: JDK 11/17 `JdkLayouts` coverage; real-world leak dump trial; GraalVM native client (v2).

### 9. Skill distribution — DONE
- [x] One skill source (`plugins/heap-dump/skills/heap-dump/SKILL.md`), tool-neutral wording (no tool-specific time limits)
- [x] `hd skill install|status|uninstall` for Claude Code, Codex, GitHub Copilot; personal or `--project` scope;
      marker file with version + hash (outdated / modified / foreign detection); launcher path written into the skill
- [x] Claude Code plugin + marketplace manifest (`claude plugin validate` passes; warnings: no icon)
- [x] Version from one place (Gradle `version` → jar manifest → `hd --version`, skill marker)
- [ ] Verify which project folder Codex actually loads (`.agents/skills` vs `.codex/skills`; docs disagree)

## v2 backlog

- `hd diff <s1> <s2>` (growth by class / dominator / retained)
- GraalVM native-image client
- Own core implementing `engine-api` on top of `hprof-stream`, validated by `engine-tck`
