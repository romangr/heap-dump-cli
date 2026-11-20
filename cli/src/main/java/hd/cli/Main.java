package hd.cli;

import hd.protocol.HdPaths;
import hd.protocol.Request;
import hd.protocol.Response;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** The {@code hd} command line. Every command prints one JSON envelope (or a table with --format table). */
@Command(name = "hd", mixinStandardHelpOptions = true, versionProvider = Main.Version.class,
        description = {"Java heap dump analyzer for agents and terminals (engine: Eclipse MAT core).",
                "",
                "Typical flow: hd open <dump> → hd wait <session> → hd info / detect / dominators / path-to-roots.",
                "Output is JSON; addresses (0x…) from one command can be passed to the next."},
        subcommands = {Main.Triage.class, Main.Open.class, Main.Wait.class, Main.Sessions.class, Main.Close.class,
                Main.Info.class, Main.Histogram.class, Main.Dominators.class, Main.ObjectCmd.class, Main.Refs.class,
                Main.PathToRoots.class, Main.Instances.class, Main.Threads.class, Main.Detect.class, Main.Oql.class,
                Main.DaemonCmd.class, SkillCmd.class, CommandLine.HelpCommand.class})
public final class Main {

    /** Version from the jar manifest (set by the build); "dev" when running from classes. */
    static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"hd " + version()};
        }
    }

    static List<String> argv = List.of();

    public static void main(String[] args) {
        argv = Arrays.asList(args);
        CommandLine cl = new CommandLine(new Main());
        cl.setCaseInsensitiveEnumValuesAllowed(true);
        // usage errors also come back as a JSON envelope, so agents can handle them like any other error
        cl.setParameterExceptionHandler((ex, a) -> {
            String usage = ex.getCommandLine().getUsageMessage(CommandLine.Help.Ansi.OFF);
            Response r = Response.error(hd.protocol.ErrorCode.INVALID_ARGUMENT, ex.getMessage(),
                    Map.of("usage", usage.strip()));
            boolean table = argv.contains("--format=table") || (argv.contains("--format") && argv.indexOf("--format") + 1 < argv.size()
                    && argv.get(argv.indexOf("--format") + 1).equalsIgnoreCase("table"));
            return Output.print(r, table ? Output.Format.TABLE : Output.Format.JSON, false, System.out);
        });
        System.exit(cl.execute(args));
    }

    // ---- shared options

    static final class OutputOpts {
        @Option(names = "--format", description = "json (default) or table", defaultValue = "json")
        Output.Format format;

        @Option(names = "--compact", description = "single-line JSON")
        boolean compact;
    }

    static final class PageOpts {
        @Option(names = "--limit", description = "rows per page (default ${DEFAULT-VALUE})", defaultValue = "25")
        int limit;

        @Option(names = "--offset", description = "rows to skip", defaultValue = "0")
        int offset;
    }

    static final class WaitOpts {
        @Option(names = "--timeout", defaultValue = "8m",
                description = "how long to wait for a result before returning a job id to `hd wait` on "
                        + "(e.g. 90s, 8m; default ${DEFAULT-VALUE}, below the agent's 10-minute command limit)")
        String timeout;

        int seconds() {
            return parseDuration(timeout);
        }
    }

    static final class JvmOpts {
        @Option(names = "-X", paramLabel = "<opt>",
                description = "JVM option for the analysis daemon, written as for java: -Xmx48g, -XX:+UseZGC (repeatable). "
                        + "Applies when the daemon starts; a different -Xmx than the running daemon's is an error.")
        List<String> x = new ArrayList<>();

        List<String> opts() {
            return x.stream().map(o -> "-X" + o).toList();
        }
    }

    static int parseDuration(String s) {
        String v = s.trim().toLowerCase();
        try {
            if (v.endsWith("ms")) return (int) Math.max(1, Long.parseLong(v.substring(0, v.length() - 2)) / 1000);
            if (v.endsWith("s")) return Integer.parseInt(v.substring(0, v.length() - 1));
            if (v.endsWith("m")) return Integer.parseInt(v.substring(0, v.length() - 1)) * 60;
            if (v.endsWith("h")) return Integer.parseInt(v.substring(0, v.length() - 1)) * 3600;
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new CommandLine.ParameterException(new CommandLine(new Main()), "invalid duration: " + s + " (use e.g. 90s or 8m)");
        }
    }

    /** Base for commands that send one request. */
    abstract static class Base implements Callable<Integer> {
        @Mixin
        OutputOpts output;

        abstract String cmd();

        abstract Map<String, Object> args();

        List<String> jvmOpts() {
            return List.of();
        }

        @Override
        public Integer call() {
            DaemonClient client = new DaemonClient(HdPaths.fromEnvironment());
            Response r = client.call(new Request(cmd(), args()), jvmOpts());
            return Output.print(Output.withNext(r, argv), output.format, output.compact, System.out);
        }

        static Map<String, Object> map(Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) {
                if (kv[i + 1] != null && !(kv[i + 1] instanceof Boolean b && !b)) m.put((String) kv[i], kv[i + 1]);
            }
            return m;
        }

        static String abs(Path p) {
            return p.toAbsolutePath().normalize().toString();
        }
    }

    // ---- lifecycle

    @Command(name = "triage", description = {"Streaming first look without indexing: per-class counts and estimated sizes, "
            + "biggest arrays, GC roots, thread stacks. Constant memory; use when the dump is too big to open."})
    static final class Triage extends Base {
        @Parameters(paramLabel = "<dump>") Path dump;
        @Mixin PageOpts page;
        @Mixin WaitOpts wait;
        @Mixin JvmOpts jvm;

        String cmd() { return "triage"; }

        Map<String, Object> args() { return map("dump", abs(dump), "limit", page.limit, "offset", page.offset, "wait_s", wait.seconds()); }

        List<String> jvmOpts() { return jvm.opts(); }
    }

    @Command(name = "open", description = {"Open a dump. Returns at once with a session id while indexing runs in the "
            + "background (minutes for large dumps): follow with `hd wait <session>`.",
            "Fails with INSUFFICIENT_MEMORY if the estimate exceeds the daemon's -Xmx."})
    static final class Open extends Base {
        @Parameters(paramLabel = "<dump>") Path dump;
        @Option(names = "--force", description = "skip the memory check") boolean force;
        @Option(names = "--wait", description = "wait up to this long for indexing to finish (e.g. 5m)") String wait;
        @Mixin JvmOpts jvm;

        String cmd() { return "open"; }

        Map<String, Object> args() {
            return map("dump", abs(dump), "force", force, "wait_s", wait == null ? null : parseDuration(wait));
        }

        List<String> jvmOpts() { return jvm.opts(); }
    }

    @Command(name = "wait", description = "Wait for a session to finish indexing (s:…) or for a job result (j:…).")
    static final class Wait extends Base {
        @Parameters(paramLabel = "<id>", description = "session (s:…) or job (j:…) id") String id;
        @Option(names = "--timeout", defaultValue = "8m", description = "maximum wait (default ${DEFAULT-VALUE})") String timeout;

        String cmd() { return "wait"; }

        Map<String, Object> args() { return map("id", id, "timeout_s", parseDuration(timeout)); }
    }

    @Command(name = "sessions", description = "List open sessions.")
    static final class Sessions extends Base {
        String cmd() { return "sessions"; }

        Map<String, Object> args() { return map(); }
    }

    @Command(name = "close", description = "Close a session and free its memory (indexes stay on disk).")
    static final class Close extends Base {
        @Parameters(paramLabel = "<session>") String session;

        String cmd() { return "close"; }

        Map<String, Object> args() { return map("session", session); }
    }

    // ---- queries

    abstract static class Query extends Base {
        @Parameters(index = "0", paramLabel = "<session>") String session;
        @Mixin WaitOpts wait;
        @Option(names = "--show-values", description = "show string and char/byte array contents (redacted by default)")
        boolean showValues;

        Map<String, Object> base(Object... more) {
            Map<String, Object> m = map("session", session, "wait_s", wait.seconds(), "show_values", showValues);
            m.putAll(map(more));
            return m;
        }
    }

    @Command(name = "info", description = "Overview: heap size, object counts, biggest retainers and classes.")
    static final class Info extends Query {
        String cmd() { return "info"; }

        Map<String, Object> args() { return base(); }
    }

    @Command(name = "histogram", description = "Instances and sizes per class, package or class loader.")
    static final class Histogram extends Query {
        @Option(names = "--by", defaultValue = "class", description = "class, package or loader") String by;
        @Option(names = "--filter", description = "regex on the class/package/loader name") String filter;
        @Option(names = "--sort", defaultValue = "shallow", description = "shallow, count or retained (slower: visits every object)") String sort;
        @Mixin PageOpts page;

        String cmd() { return "histogram"; }

        Map<String, Object> args() { return base("by", by, "filter", filter, "sort", sort, "limit", page.limit, "offset", page.offset); }
    }

    @Command(name = "dominators", description = "Dominator tree: what retains the most memory (top level, or below --root).")
    static final class Dominators extends Query {
        @Option(names = "--root", description = "address of the object whose dominated children to list") String root;
        @Option(names = "--group-by", description = "class: summarize children per class") String groupBy;
        @Mixin PageOpts page;

        String cmd() { return "dominators"; }

        Map<String, Object> args() { return base("root", root, "group_by", groupBy, "limit", page.limit, "offset", page.offset); }
    }

    @Command(name = "object", description = "One object: fields (or array elements), GC root status, dominator.")
    static final class ObjectCmd extends Query {
        @Parameters(index = "1", paramLabel = "<addr>") String addr;
        @Option(names = "--no-fields", description = "omit fields") boolean noFields;
        @Mixin PageOpts page;

        String cmd() { return "object"; }

        Map<String, Object> args() { return base("addr", addr, "no_fields", noFields, "limit", page.limit, "offset", page.offset); }
    }

    @Command(name = "refs", description = "Incoming (--in) or outgoing (--out) references of an object.")
    static final class Refs extends Query {
        @Parameters(index = "1", paramLabel = "<addr>") String addr;
        @Option(names = "--in", description = "who references this object") boolean in;
        @Option(names = "--out", description = "what this object references (default)") boolean out;
        @Mixin PageOpts page;

        String cmd() { return "refs"; }

        Map<String, Object> args() { return base("addr", addr, "direction", in ? "in" : "out", "limit", page.limit, "offset", page.offset); }
    }

    @Command(name = "path-to-roots", description = "Shortest paths from an object to GC roots: what keeps it alive.")
    static final class PathToRoots extends Query {
        @Parameters(index = "1", paramLabel = "<addr>") String addr;
        @Option(names = "--exclude", defaultValue = "weak,soft,phantom",
                description = "reference kinds to ignore: weak, soft, phantom, final, or none (default ${DEFAULT-VALUE})") String exclude;
        @Option(names = "--max-paths", defaultValue = "5") int maxPaths;

        String cmd() { return "path-to-roots"; }

        Map<String, Object> args() { return base("addr", addr, "exclude", exclude, "max_paths", maxPaths); }
    }

    @Command(name = "instances", description = "Instances of a class (fully qualified; arrays as byte[]), largest first.")
    static final class Instances extends Query {
        @Parameters(index = "1", paramLabel = "<class>") String className;
        @Option(names = "--sort", defaultValue = "retained", description = "retained (default) or shallow") String sort;
        @Mixin PageOpts page;

        String cmd() { return "instances"; }

        Map<String, Object> args() { return base("class", className, "sort", sort, "limit", page.limit, "offset", page.offset); }
    }

    @Command(name = "threads", description = "Threads with stacks, sorted by memory retained from their stack locals.")
    static final class Threads extends Query {
        @Option(names = "--with-locals", description = "list each frame's local objects") boolean withLocals;
        @Option(names = "--thread", description = "only this thread (name substring or 0x address), with its full stack") String thread;
        @Mixin PageOpts page;

        String cmd() { return "threads"; }

        Map<String, Object> args() { return base("with_locals", withLocals, "thread", thread, "limit", page.limit, "offset", page.offset); }
    }

    @Command(name = "detect", description = {"Run leak/waste detectors and report findings with evidence and next commands.",
            "Default: big-retainers, classloader, collections, direct-buffers, threadlocal. Opt-in: dup-strings."})
    static final class Detect extends Query {
        @Option(names = "--only", split = ",", description = "detector ids to run") List<String> only = new ArrayList<>();
        @Option(names = "--all", description = "run every detector, including opt-in ones") boolean all;

        String cmd() { return "detect"; }

        Map<String, Object> args() { return base("only", all ? List.of("all") : only.isEmpty() ? null : only); }
    }

    @Command(name = "oql", description = "Run an OQL query (Eclipse MAT syntax). String cells are redacted unless --show-values.")
    static final class Oql extends Query {
        @Parameters(index = "1", paramLabel = "<query>") String query;
        @Mixin PageOpts page;

        String cmd() { return "oql"; }

        Map<String, Object> args() { return base("query", query, "limit", page.limit, "offset", page.offset); }
    }

    // ---- daemon management

    @Command(name = "daemon", description = "Manage the analysis daemon (normally started automatically).",
            subcommands = {DaemonCmd.Start.class, DaemonCmd.Stop.class, DaemonCmd.Restart.class, DaemonCmd.Status.class})
    static final class DaemonCmd {

        abstract static class Sub implements Callable<Integer> {
            @Mixin OutputOpts output;

            int print(Response r) {
                return Output.print(r, output.format, output.compact, System.out);
            }

            DaemonClient client() {
                return new DaemonClient(HdPaths.fromEnvironment());
            }
        }

        @Command(name = "start", description = "Start the daemon (JVM options: -X…, then HD_JAVA_OPTS, then config, then automatic).")
        static final class Start extends Sub {
            @Mixin JvmOpts jvm;

            public Integer call() {
                DaemonClient c = client();
                if (c.isRunning()) {
                    Response status = c.send(new Request("status", Map.of()));
                    if (!jvm.opts().isEmpty()) {
                        Response check = c.call(new Request("status", Map.of()), jvm.opts());
                        if (!check.ok()) return print(check);
                    }
                    return print(status);
                }
                Response r = c.start(jvm.opts());
                return print(r.ok() ? c.send(new Request("status", Map.of())) : r);
            }
        }

        @Command(name = "stop", description = "Stop the daemon (sessions are closed, indexes stay on disk).")
        static final class Stop extends Sub {
            public Integer call() {
                return print(client().stop());
            }
        }

        @Command(name = "restart", description = "Restart the daemon, e.g. with a different -Xmx.")
        static final class Restart extends Sub {
            @Mixin JvmOpts jvm;

            public Integer call() {
                DaemonClient c = client();
                Response stopped = c.stop();
                if (!stopped.ok()) return print(stopped);
                Response r = c.start(jvm.opts());
                return print(r.ok() ? c.send(new Request("status", Map.of())) : r);
            }
        }

        @Command(name = "status", description = "Daemon pid, -Xmx and its source, memory use, sessions and jobs.")
        static final class Status extends Sub {
            public Integer call() {
                DaemonClient c = client();
                if (!c.isRunning()) {
                    return print(Response.ok(null, Map.of("running", false,
                            "would_start_with", c.resolve(List.of())), null, List.of("hd daemon start")));
                }
                return print(c.send(new Request("status", Map.of())));
            }
        }
    }
}
