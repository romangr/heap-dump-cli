package hd.daemon;

import hd.protocol.HdPaths;

/**
 * Entry point of the daemon JVM. Started by the CLI (detached, stdout/stderr to ~/.hd/daemon.log); not meant to
 * be run by hand.
 */
public final class DaemonMain {

    private DaemonMain() {}

    public static void main(String[] args) throws Exception {
        ignoreHangup();
        HdPaths paths = HdPaths.fromEnvironment();
        Daemon daemon = new Daemon(DaemonConfig.load(paths));
        if (!daemon.start()) {
            System.err.println("another hd daemon is already running for " + paths.home());
            System.exit(0);
        }
        System.err.println("hd daemon listening on " + paths.socket() + " (pid " + ProcessHandle.current().pid() + ")");
        // the accept thread keeps the JVM alive
    }

    /** Survive the terminal that started the first CLI call being closed. */
    @SuppressWarnings("removal")
    private static void ignoreHangup() {
        try {
            sun.misc.Signal.handle(new sun.misc.Signal("HUP"), sun.misc.SignalHandler.SIG_IGN);
        } catch (Throwable ignored) {
            // not available on this platform
        }
    }
}
