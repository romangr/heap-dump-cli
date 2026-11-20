package hd.fixtures.model;

/** Loaded several times by separate class loaders in the "leaks" scenario (simulates redeploys). */
public final class PluginComponent {
    public static final byte[] STATE = new byte[64 * 1024];
}
