package hd.analysis;

import hd.engine.api.Addresses;
import hd.engine.api.FieldValue;
import hd.engine.api.ObjectKind;
import hd.engine.api.ObjectView;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Knowledge of JDK class internals (field names and layouts), isolated in one place.
 * Supports JDK 8 (char[] strings), 11 and 17 (compact strings), 21+ (Thread.FieldHolder).
 */
public final class JdkLayouts {

    private JdkLayouts() {}

    // ---- java.lang.String

    /** Character length of a String, without reading its content. Empty if not a decodable String. */
    public static OptionalInt stringLength(Heap heap, ObjectView str) {
        long value = str.refField("value");
        if (value == Addresses.NULL) return OptionalInt.empty();
        ObjectView v = heap.object(value);
        if (v.kind() != ObjectKind.PRIMITIVE_ARRAY) return OptionalInt.empty();
        if (v.className().equals("char[]")) return OptionalInt.of(v.length());
        int coder = intOf(str.field("coder")).orElse(0);
        return OptionalInt.of(v.length() >> coder);
    }

    /** Full content of a String (callers must apply redaction). Empty if not decodable. */
    public static Optional<String> stringValue(Heap heap, ObjectView str, int maxChars) {
        long value = str.refField("value");
        if (value == Addresses.NULL) return Optional.empty();
        ObjectView v = heap.object(value);
        if (v.kind() != ObjectKind.PRIMITIVE_ARRAY) return Optional.empty();
        if (v.className().equals("char[]")) {
            char[] chars = (char[]) v.readPrimitives(0, Math.min(v.length(), maxChars));
            return Optional.of(new String(chars));
        }
        int coder = intOf(str.field("coder")).orElse(0);
        int bytes = Math.min(v.length(), coder == 0 ? maxChars : maxChars * 2);
        byte[] raw = (byte[]) v.readPrimitives(0, bytes);
        return Optional.of(coder == 0 ? new String(raw, StandardCharsets.ISO_8859_1) : new String(raw, StandardCharsets.UTF_16LE));
    }

    /** Reads a String that is not user data (thread names, class loader names). */
    public static String metadataString(Heap heap, long address) {
        if (address == Addresses.NULL) return null;
        ObjectView s = heap.object(address);
        if (!s.className().equals("java.lang.String")) return null;
        return stringValue(heap, s, 256).orElse(null);
    }

    // ---- java.lang.Thread

    public record ThreadInfo(String name, Boolean daemon, String state, long threadLocals) {}

    public static ThreadInfo thread(Heap heap, ObjectView t) {
        String name = metadataString(heap, t.refField("name"));
        Boolean daemon = boolOf(t.field("daemon")).orElse(null);
        OptionalInt status = intOf(t.field("threadStatus"));
        long holder = t.refField("holder"); // JDK 19+: Thread.FieldHolder
        if (holder != Addresses.NULL) {
            ObjectView h = heap.object(holder);
            if (daemon == null) daemon = boolOf(h.field("daemon")).orElse(null);
            if (status.isEmpty()) status = intOf(h.field("threadStatus"));
        }
        String state = status.isPresent() ? threadState(status.getAsInt()) : null;
        return new ThreadInfo(name, daemon, state, t.refField("threadLocals"));
    }

    /** JVMTI thread status bits → Thread.State name (mirrors jdk.internal.misc.VM.toThreadState). */
    static String threadState(int status) {
        if ((status & 0x0004) != 0) return "RUNNABLE";
        if ((status & 0x0400) != 0) return "BLOCKED";
        if ((status & 0x0010) != 0) return "WAITING";
        if ((status & 0x0020) != 0) return "TIMED_WAITING";
        if ((status & 0x0002) != 0) return "TERMINATED";
        if ((status & 0x0001) == 0) return "NEW";
        return "RUNNABLE";
    }

    // ---- collections

    /**
     * @param size number of elements
     * @param capacity slots in the backing array, or -1 if unknown / not array-backed
     * @param backingArray address of the backing array, or NULL
     */
    public record CollectionShape(String kind, long size, long capacity, long backingArray) {
        public double fillRatio() {
            return capacity > 0 ? (double) size / capacity : 1.0;
        }
    }

    /** Shape of well-known JDK collections, or empty for other classes. */
    public static Optional<CollectionShape> collection(Heap heap, ObjectView o) {
        String cls = o.className();
        long cAddr = o.classAddress();
        try {
            if (cls.equals("java.util.HashSet") || cls.equals("java.util.LinkedHashSet")) {
                long map = o.refField("map");
                return map == Addresses.NULL ? Optional.empty()
                        : collection(heap, heap.object(map)).map(s -> new CollectionShape("set", s.size(), s.capacity(), s.backingArray()));
            }
            if (heap.isSubclassOf(cAddr, "java.util.HashMap") || heap.isSubclassOf(cAddr, "java.util.WeakHashMap")) {
                return Optional.of(shape("map", heap, intOf(o.field("size")).orElse(0), o.refField("table")));
            }
            if (heap.isSubclassOf(cAddr, "java.util.Hashtable")) {
                return Optional.of(shape("map", heap, intOf(o.field("count")).orElse(0), o.refField("table")));
            }
            if (heap.isSubclassOf(cAddr, "java.util.concurrent.ConcurrentHashMap")) {
                long size = longOf(o.field("baseCount")).orElse(0L);
                long cells = o.refField("counterCells");
                if (cells != Addresses.NULL) {
                    ObjectView arr = heap.object(cells);
                    for (long cell : arr.readReferences(0, arr.length())) {
                        if (cell != Addresses.NULL) size += longOf(heap.object(cell).field("value")).orElse(0L);
                    }
                }
                return Optional.of(shape("map", heap, size, o.refField("table")));
            }
            if (heap.isSubclassOf(cAddr, "java.util.IdentityHashMap")) {
                CollectionShape s = shape("map", heap, intOf(o.field("size")).orElse(0), o.refField("table"));
                return Optional.of(new CollectionShape("map", s.size(), s.capacity() / 2, s.backingArray()));
            }
            if (heap.isSubclassOf(cAddr, "java.util.ArrayList")) {
                return Optional.of(shape("list", heap, intOf(o.field("size")).orElse(0), o.refField("elementData")));
            }
            if (heap.isSubclassOf(cAddr, "java.util.Vector")) {
                return Optional.of(shape("list", heap, intOf(o.field("elementCount")).orElse(0), o.refField("elementData")));
            }
            if (heap.isSubclassOf(cAddr, "java.util.ArrayDeque")) {
                long arr = o.refField("elements");
                int head = intOf(o.field("head")).orElse(0), tail = intOf(o.field("tail")).orElse(0);
                long cap = arr == Addresses.NULL ? 0 : heap.object(arr).length();
                long size = cap == 0 ? 0 : Math.floorMod(tail - head, (int) cap);
                return Optional.of(new CollectionShape("deque", size, cap, arr));
            }
            if (heap.isSubclassOf(cAddr, "java.util.LinkedList")) {
                return Optional.of(new CollectionShape("list", intOf(o.field("size")).orElse(0), -1, Addresses.NULL));
            }
            if (heap.isSubclassOf(cAddr, "java.util.TreeMap")) {
                return Optional.of(new CollectionShape("map", intOf(o.field("size")).orElse(0), -1, Addresses.NULL));
            }
            if (cls.equals("java.util.concurrent.ConcurrentLinkedQueue") || cls.equals("java.util.concurrent.LinkedBlockingQueue")
                    || cls.equals("java.util.concurrent.ArrayBlockingQueue")) {
                OptionalInt count = intOf(o.field("count"));
                if (count.isEmpty()) {
                    long atomic = o.refField("count");
                    if (atomic != Addresses.NULL) count = intOf(heap.object(atomic).field("value"));
                }
                long items = o.refField("items");
                long cap = items == Addresses.NULL ? -1 : heap.object(items).length();
                return count.isPresent() ? Optional.of(new CollectionShape("queue", count.getAsInt(), cap, items)) : Optional.empty();
            }
        } catch (RuntimeException e) {
            return Optional.empty(); // unexpected layout: not a collection we understand
        }
        return Optional.empty();
    }

    private static CollectionShape shape(String kind, Heap heap, long size, long array) {
        long cap = array == Addresses.NULL ? 0 : heap.object(array).length();
        return new CollectionShape(kind, size, cap, array);
    }

    // ---- java.nio.DirectByteBuffer

    /**
     * Capacity of a direct buffer that owns memory allocated by ByteBuffer.allocateDirect (counted against
     * -XX:MaxDirectMemorySize). Returns 0 for slices/duplicates (attachment set), memory-mapped files (file
     * descriptor set) and buffers wrapping foreign native memory (no cleaner, e.g. JNI NewDirectByteBuffer or the
     * JDK's module image).
     */
    public static long directBufferOwnedCapacity(ObjectView buffer) {
        if (buffer.refField("att") != Addresses.NULL || isMappedFile(buffer) || buffer.refField("cleaner") == Addresses.NULL) {
            return 0;
        }
        return intOf(buffer.field("capacity")).orElse(0);
    }

    /** Whether the buffer maps a file (FileChannel.map) rather than allocated direct memory. */
    public static boolean isMappedFile(ObjectView buffer) {
        return buffer.refField("fd") != Addresses.NULL;
    }

    // ---- field helpers

    public static OptionalInt intOf(Optional<FieldValue> f) {
        if (f.isEmpty() || !(f.get().value() instanceof Number n)) return OptionalInt.empty();
        return OptionalInt.of(n.intValue());
    }

    public static Optional<Long> longOf(Optional<FieldValue> f) {
        if (f.isEmpty() || !(f.get().value() instanceof Number n)) return Optional.empty();
        return Optional.of(n.longValue());
    }

    public static Optional<Boolean> boolOf(Optional<FieldValue> f) {
        if (f.isEmpty() || !(f.get().value() instanceof Boolean b)) return Optional.empty();
        return Optional.of(b);
    }
}
