package hd.fixtures.model;

public final class DiamondNode {
    public final String name;
    public DiamondNode a;
    public DiamondNode b;
    public Payload payload;

    public DiamondNode(String name) {
        this.name = name;
    }
}
