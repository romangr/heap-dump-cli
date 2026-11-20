package hd.fixtures.model;

/** GC-root anchor for the "graph" scenario. */
public final class FixtureRoot {
    public static FixtureRoot INSTANCE;

    public ChainNode chain;
    public DiamondNode diamond;
    public DiamondNode cycle;
    public Payload big;
}
