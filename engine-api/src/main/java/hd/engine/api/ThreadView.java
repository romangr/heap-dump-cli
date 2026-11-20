package hd.engine.api;

import java.util.List;

/**
 * Stack data of one thread. Name, state etc. are read from the Thread object's fields by the analysis layer.
 *
 * @param address the java.lang.Thread object
 * @param frames top frame first
 */
public record ThreadView(long address, List<Frame> frames) {

    /**
     * @param method fully qualified method, e.g. "java.lang.Object.wait0"
     * @param location source location as reported by the dump, e.g. "Object.java:338" or "Native Method"; may be null
     * @param locals addresses of objects referenced from this frame's local variables
     */
    public record Frame(String method, String location, long[] locals) {}
}
