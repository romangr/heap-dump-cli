package hd.fixtures.model;

public final class ChainNode {
    public final int index;
    public final Payload payload;
    public final ChainNode next;

    public ChainNode(int index, Payload payload, ChainNode next) {
        this.index = index;
        this.payload = payload;
        this.next = next;
    }
}
