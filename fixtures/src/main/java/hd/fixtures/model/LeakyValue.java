package hd.fixtures.model;

/** Value stored in a ThreadLocal of a pool thread and never removed. */
public final class LeakyValue {
    public final byte[] data;

    public LeakyValue(int size) {
        this.data = new byte[size];
    }
}
