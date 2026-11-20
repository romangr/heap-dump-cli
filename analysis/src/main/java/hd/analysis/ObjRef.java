package hd.analysis;

import hd.engine.api.Addresses;
import hd.engine.api.ObjectKind;
import hd.engine.api.ObjectView;

import java.util.OptionalInt;

/**
 * Compact, reusable reference to an object in results. {@code addr} can be passed to the next command.
 *
 * @param label short description that respects redaction (thread name, collection size, string length, …), or null
 */
public record ObjRef(String addr, String cls, Size shallow, Size retained, String label) {

    public static ObjRef of(Heap heap, long address, Redaction redaction) {
        ObjectView o = heap.object(address);
        long retained = heap.snapshot().retainedSize(address);
        return new ObjRef(Addresses.hex(address), displayClass(o), heap.size(o.shallowSize()), heap.size(retained),
                label(heap, o, redaction));
    }

    /** Without retained size (cheaper; for long lists where retained is not needed). */
    public static ObjRef shallow(Heap heap, long address, Redaction redaction) {
        ObjectView o = heap.object(address);
        return new ObjRef(Addresses.hex(address), displayClass(o), heap.size(o.shallowSize()), null, label(heap, o, redaction));
    }

    static String displayClass(ObjectView o) {
        return o.kind() == ObjectKind.CLASS ? "java.lang.Class" : o.className();
    }

    /** One-line, redaction-safe description of an object, or null when nothing useful can be said. */
    public static String label(Heap heap, ObjectView o, Redaction redaction) {
        try {
            switch (o.kind()) {
                case CLASS:
                    return "class " + heap.classView(o.address()).name();
                case OBJECT_ARRAY:
                case PRIMITIVE_ARRAY:
                    return "length=" + o.length();
                default:
                    break;
            }
            String cls = o.className();
            if (cls.equals("java.lang.String")) {
                if (redaction.showValues()) {
                    return JdkLayouts.stringValue(heap, o, 120).map(s -> "\"" + truncate(s, 80) + "\"").orElse(null);
                }
                OptionalInt len = JdkLayouts.stringLength(heap, o);
                return len.isPresent() ? "len=" + len.getAsInt() + " (value redacted)" : null;
            }
            if (heap.isSubclassOf(o.classAddress(), "java.lang.Thread")) {
                JdkLayouts.ThreadInfo t = JdkLayouts.thread(heap, o);
                return t.name() == null ? null : "thread \"" + t.name() + "\"";
            }
            if (heap.isSubclassOf(o.classAddress(), "java.lang.ClassLoader")) {
                String name = JdkLayouts.metadataString(heap, o.refField("name"));
                return name == null ? "class loader" : "class loader \"" + name + "\"";
            }
            var shape = JdkLayouts.collection(heap, o);
            if (shape.isPresent()) {
                var s = shape.get();
                return s.capacity() >= 0 ? "size=" + s.size() + " capacity=" + s.capacity() : "size=" + s.size();
            }
            if (cls.equals("java.nio.DirectByteBuffer") || cls.equals("java.nio.DirectByteBufferR")) {
                return "direct capacity=" + JdkLayouts.intOf(o.field("capacity")).orElse(0)
                        + (o.refField("att") != Addresses.NULL ? " (view)" : "");
            }
        } catch (RuntimeException e) {
            return null;
        }
        return null;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
