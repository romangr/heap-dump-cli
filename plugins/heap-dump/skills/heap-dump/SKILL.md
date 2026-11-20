---
name: heap-dump
description: Analyze Java heap dumps (.hprof / .hprof.gz) with the `hd` CLI (Eclipse MAT engine) — find memory leaks, what retains memory, why objects stay alive, OutOfMemoryError causes, ThreadLocal / class loader / collection / direct buffer leaks, duplicate strings. Use when the user has a heap dump, an OutOfMemoryError, a memory leak, or high JVM memory usage to investigate.
---

# Heap dump analysis with `hd`

`hd` is a CLI over an analysis daemon. Every command prints one JSON envelope:

```json
{"ok": true, "engine": "mat", "data": {...}, "page": {"limit":25,"offset":0,"total":1834,"next":"hd ... --offset 25"}, "hints": ["hd ..."]}
{"ok": false, "error": {"code": "SESSION_NOT_READY", "message": "...", "details": {...}}}
```

- `addr` values (`0x7f3a10c8`) can be passed straight to the next command.
- `hints` are good next steps; `page.next` fetches the next page. Defaults are small (25 rows) on purpose: page only when needed.
- Sizes come as `{"bytes":…, "human":"4.0 GiB", "pct": 12.5}` (pct = share of used heap).
- Check `hd` is available (`hd --version`). If it is not on PATH, use the launcher path noted at the end of this file
  (added by `hd skill install`); otherwise ask the user to install hd (see the project README).

## Workflow

1. **Open** (returns immediately, indexing runs in the background):
   `hd open /path/dump.hprof` → note the session id (`s:3fa2c1`).
   - `INSUFFICIENT_MEMORY`: report the estimate from `error.details`. Offer `hd daemon restart -Xmx<size>` if the machine has
     the RAM (check with the user for large values), or `hd triage <dump>` for a quick look. Never use `--force` on your own.
   - `INSUFFICIENT_DISK`: indexes need ~1.5x the dump size; report and ask where to put them (`index.dir` in `~/.hd/config.properties`).
2. **Wait** until ready: `hd wait s:3fa2c1` (blocks up to `--timeout`, default 8 min). If `state` is still `indexing`, run it again — large dumps
   take a while (about 90 s per 3–4 GB). Tell the user the progress/eta while waiting.
3. **Overview**: `hd info s:…` — heap size, object count, top retainers, top classes.
4. **Detect**: `hd detect s:…` — findings with severity, explanation, evidence addresses and `next` commands.
   `dup-strings` is opt-in (`--only dup-strings`), it reads every string.
5. **Dig in** on the biggest findings / retainers:
   - `hd dominators s:… [--root <addr>] [--group-by class]` — what retains the memory, level by level.
   - `hd path-to-roots s:… <addr>` — why an object is alive (weak/soft/phantom references excluded by default).
   - `hd object s:… <addr>` — fields, array elements, GC root status, dominator.
   - `hd refs s:… <addr> --in|--out`, `hd instances s:… <fully.qualified.Class> --sort retained`.
   - `hd threads s:… [--thread <name>] [--with-locals]` — stacks, memory held by stack locals.
   - `hd histogram s:… [--by class|package|loader] [--filter <regex>] [--sort shallow|count|retained]`
     (`--sort retained` visits every object: slower on big dumps).
   - `hd oql s:… "SELECT * FROM java.util.HashMap m WHERE m.size > 100000"` (Eclipse MAT OQL).
6. **Summarize** for the user: the root cause chain (GC root → … → accumulation point), how much memory it holds
   (bytes and % of heap), the evidence addresses, and a concrete fix suggestion (bounded cache, `ThreadLocal.remove()`,
   closing resources, fixing the redeploy leak, …). Separate confirmed facts from hypotheses.

## Long-running commands

Any query may return a job instead of a result when it runs longer than `--timeout` (default 8m):
`{"data": {"job": "j:ab12cd", "state": "running", "progress": 0.4}, "hints": ["hd wait j:ab12cd"]}`.
Run `hd wait j:ab12cd` until it returns the result. If your environment limits how long one command may run, pass
`--timeout` (and `hd wait … --timeout`) below that limit, e.g. `--timeout 90s`; nothing is lost, the work continues
in the daemon.

## Rules

- **Never pass `--show-values` unless the user explicitly asks to see string/array contents.** Dumps contain passwords,
  tokens and personal data; by default contents show as `{"len": 32, "redacted": true}` and labels as `len=12 (value redacted)`.
  Class, field and thread names are always visible.
- Do not change the daemon's memory (`-Xmx`) without telling the user; a restart drops open sessions (indexes are kept,
  reopening is fast).
- Prefer following `hints` and `next` over guessing addresses or class names.
- Indexes are written next to the dump (or under `~/.hd/index`); mention this if disk space matters.

## Error codes

| code | exit | what to do |
|---|---|---|
| `SESSION_NOT_READY` | 3 | `hd wait <session>` |
| `SESSION_NOT_FOUND` | 4 | `hd open <dump>` again (the daemon restarts after 30 min idle; reopening uses existing indexes) |
| `OBJECT_NOT_FOUND`, `FILE_NOT_FOUND`, `JOB_NOT_FOUND` | 4 | check the address / path / job id |
| `INVALID_ARGUMENT` | 2 | fix the command (details may include usage) |
| `INSUFFICIENT_MEMORY`, `INSUFFICIENT_DISK` | 5 | see workflow step 1 |
| `DAEMON_XMX_MISMATCH` | 5 | the running daemon has another `-Xmx`; use the hint `hd daemon restart -Xmx…` if the user wants that size |
| `ENGINE_OOM` | 5 | the daemon ran out of memory and exited: restart with a larger `-Xmx` (ask the user) or use `hd triage` |
| `CAPABILITY_UNSUPPORTED` | 6 | the engine cannot do this (e.g. OQL); use the other commands |
| `CORRUPT_DUMP` | 7 | the file is truncated or not an HPROF heap dump |

## Triage (no index, constant memory)

`hd triage <dump>` streams the file once: per-class counts and estimated shallow sizes, biggest arrays (with
addresses), GC root counts and thread stacks. No retained sizes. Use it when the dump is too large to open or the user
wants a quick first look. Counts include unreachable objects that a full `open` discards.

## Daemon

Started automatically. `hd daemon status` shows pid, `-Xmx` and where it came from (`cli`, `env` = `HD_JAVA_OPTS`,
`config` = `daemon.jvm-opts` in `~/.hd/config.properties`, or `auto` = 75% of RAM). JVM options are passed as for
`java`: `hd daemon start -Xmx48g -XX:+UseZGC`.
