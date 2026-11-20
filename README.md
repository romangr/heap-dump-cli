# heap-dump-cli (`hd`)

[![build](https://github.com/romangr/heap-dump-cli/actions/workflows/build.yml/badge.svg)](https://github.com/romangr/heap-dump-cli/actions/workflows/build.yml)

Command-line Java heap dump analyzer built for agents (Claude skill) and terminals, using the Eclipse Memory
Analyzer (MAT) core as its engine — behind an engine interface, so the engine can be replaced later.

- JSON output with reusable object addresses, paging, and suggested next commands
- A long-running daemon keeps the snapshot loaded; `open`/`wait` and job ids keep every call under agent time limits
- Leak detectors: big retainers (with accumulation point), ThreadLocal, duplicate class loaders, large/sparse
  collections, direct buffers, duplicate strings (opt-in)
- Streaming `triage` for dumps too large to index (3.6 GB scanned in ~1 s)
- String and char/byte array contents are redacted unless `--show-values`

## Install

Requires Java 21+ (macOS or Linux).

The latest build of `main` is published as the [continuous release](https://github.com/romangr/heap-dump-cli/releases/tag/continuous)
(replaced on every push, not a stable release):

```bash
curl -fsSL https://github.com/romangr/heap-dump-cli/releases/download/continuous/hd.tar.gz | tar -xz
export PATH="$PWD/hd/bin:$PATH"
hd --help
```

From source:

```bash
scripts/install-mat.sh          # downloads MAT 1.17.0 (SHA-512 pinned) into ~/.m2 (MAT is not on Maven Central)
./gradlew :cli:installDist      # → cli/build/install/hd
export PATH="$PWD/cli/build/install/hd/bin:$PATH"
```

### Agent skill (Claude Code, Codex, GitHub Copilot)

The skill uses the shared Agent Skills format (`SKILL.md`), so one skill serves all three agents. `hd` installs the
copy bundled with it, so the skill always matches the binary:

```bash
hd skill install                          # for every agent found in your home (~/.claude, ~/.codex, ~/.copilot)
hd skill install --target codex           # claude | codex | copilot | all
hd skill install --project path/to/repo   # into a repository instead (commit it to share with the team)
hd skill status                           # installed / outdated / modified per location
hd skill uninstall
```

| Agent | Personal | Project |
|---|---|---|
| Claude Code | `~/.claude/skills/heap-dump` | `.claude/skills/heap-dump` |
| Codex | `~/.codex/skills/heap-dump` | `.agents/skills/heap-dump` |
| GitHub Copilot | `~/.copilot/skills/heap-dump` | uses `.claude/skills` or `.agents/skills` if written, else `.github/skills` |

Run `hd skill install` again after upgrading hd (`hd skill status` reports `outdated`). Folders not created by hd, or
edited after installation, are never overwritten without `--force`. The installed skill records the path of the `hd`
launcher, so agents find it even when it is not on their PATH.

Claude Code users can alternatively install the skill as a plugin from this repository's marketplace (the `hd` binary
still has to be installed). Avoid installing both ways, or Claude sees the skill twice:

```
/plugin marketplace add romangr/heap-dump-cli
/plugin install heap-dump@heap-dump-cli
```

## Usage

```bash
hd open /dumps/app.hprof            # returns a session id, indexing runs in the background
hd wait s:3fa2c1                    # until ready (re-run if still indexing)
hd info s:3fa2c1
hd detect s:3fa2c1
hd dominators s:3fa2c1 --root 0x7f3a10c8 --group-by class
hd path-to-roots s:3fa2c1 0x7f3a10c8
hd threads s:3fa2c1 --thread worker-1 --with-locals
hd oql s:3fa2c1 "SELECT * FROM java.util.HashMap m WHERE m.size > 100000"
hd triage /dumps/huge.hprof.gz      # no index, constant memory
```

Add `--format table` for human-readable output. `hd <command> --help` lists all options.

### Memory and disk

The daemon's JVM options come from (highest first): `-X…` on the command line (`hd daemon start -Xmx48g`),
`HD_JAVA_OPTS`, `daemon.jvm-opts` in `~/.hd/config.properties`, or automatic (75% of RAM). Requesting a different
`-Xmx` than the running daemon's is an error (`DAEMON_XMX_MISMATCH`) — use `hd daemon restart -Xmx…`.

Before indexing, `open` estimates the memory needed from the file size and fails early with `INSUFFICIENT_MEMORY`
or `INSUFFICIENT_DISK`. Measured on a 3.6 GB dump with 90M small objects: indexing needs a heap of ~0.7× the dump
size (87 s); reopening with existing indexes and querying worked with 0.14×; index files take ~1.4× the dump size.

`~/.hd/config.properties`:

| key | default | |
|---|---|---|
| `daemon.jvm-opts` | | e.g. `-Xmx48g -XX:+UseZGC` |
| `daemon.idle-timeout-minutes` | 30 | |
| `daemon.max-sessions` | 1 | loaded snapshots; least recently used is closed |
| `index.dir` | next to the dump | central index directory (per-dump subdirectories) |
| `memory.ratio` / `memory.reopen-ratio` | 0.75 / 0.25 | heap per byte of dump for first open / reopen |
| `disk.index-ratio` | 1.5 | free disk per byte of dump required for indexing |
| `memory.gzip-expansion` | 4.0 | assumed uncompressed/compressed ratio for `.gz` |

## Architecture

```
cli ──socket──▶ daemon ──▶ analysis (views, detectors, redaction) ──▶ engine-api ◀── engine-mat (MAT core, no OSGi)
                       └─▶ hprof-stream (triage, own parser)                    ◀── (future own engine)
```

- `engine-api` — the boundary: heap-graph primitives addressed by heap address, plus optional capabilities
  (OQL, fast histogram, fast GC-root paths, retained-set size).
- `engine-tck` — contract suite every engine must pass (run against generated fixture dumps).
- `engine-mat` — the only module that touches MAT (enforced by `architecture` ArchUnit tests). Boots MAT's plugins
  on a plain classpath via a non-OSGi Eclipse extension registry.
- `analysis` — everything engine-independent: views, `JdkLayouts`, detectors (ServiceLoader), redaction.
- `fixtures` — generates `.hprof` fixtures with known object graphs (`./gradlew :fixtures:generateFixtures`).
- `plugins/heap-dump/skills/heap-dump/SKILL.md` — the single source of the agent skill (bundled into `hd`, the
  distribution and the Claude Code plugin; marketplace manifest in `.claude-plugin/marketplace.json`).

Design and plan: `docs/plans/2026-11-20-heap-dump-cli.md`. Tests: `./gradlew test`.
CI (`.github/workflows/build.yml`) builds and tests every push and pull request; pushes to `main` replace the
`continuous` release with `hd.zip`, `hd.tar.gz` and `SHA256SUMS`.

## License

Apache License 2.0 — see `LICENSE` and `NOTICE`.

The binary distribution bundles third-party components under their own licenses (Eclipse MAT and Eclipse Platform:
EPL-2.0; Jackson, picocli: Apache-2.0); see `THIRD-PARTY-NOTICES.md`.
