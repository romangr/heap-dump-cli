package hd.engine.api;

import java.util.List;

/**
 * @param address the root object
 * @param types why it is a root (an object can be a root for several reasons)
 * @param threadAddress for thread-related roots (locals, JNI locals) the owning thread, else {@link Addresses#NULL}
 */
public record GcRoot(long address, List<Type> types, long threadAddress) {

    public enum Type {
        SYSTEM_CLASS, THREAD_OBJECT, JAVA_LOCAL, NATIVE_STACK, THREAD_BLOCK, BUSY_MONITOR,
        JNI_GLOBAL, JNI_LOCAL, NATIVE_STATIC, FINALIZABLE, UNFINALIZED, UNREACHABLE, UNKNOWN
    }
}
