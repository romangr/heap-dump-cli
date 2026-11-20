package hd.fixtures.model;

/** Holds a byte array of a known length. */
public final class Payload {
    public final byte[] data;

    public Payload(int size) {
        this.data = new byte[size];
    }
}
