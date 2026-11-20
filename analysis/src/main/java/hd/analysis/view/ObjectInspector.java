package hd.analysis.view;

import hd.analysis.Heap;
import hd.analysis.Limits;
import hd.analysis.ObjRef;
import hd.analysis.Page;
import hd.analysis.Redaction;
import hd.engine.api.Addresses;
import hd.engine.api.FieldValue;
import hd.engine.api.GcRoot;
import hd.engine.api.ObjectKind;
import hd.engine.api.ObjectView;
import hd.engine.api.Snapshot;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/** Details of one object: fields (or static fields for a class), array elements, GC root status, dominator. */
public final class ObjectInspector {

    /**
     * @param value primitive value, an {@link ObjRef} for non-null references, null for null references,
     *              or {@link Redaction.Redacted}
     */
    public record Field(String name, String type, Object value) {}

    /**
     * @param elements element values (paged) for arrays: numbers/booleans/chars, ObjRef, or a single Redacted entry
     */
    public record Result(ObjRef obj, String kind, List<String> gcRoots, ObjRef dominator, List<Field> fields,
                         Integer length, String elementType, Page<Object> elements) {}

    private static final int MAX_FIELD_REFS = 200;

    private ObjectInspector() {}

    public static Result inspect(Heap heap, long address, boolean withFields, Limits limits, Redaction redaction) {
        Snapshot s = heap.snapshot();
        ObjectView o = heap.object(address);
        ObjRef self = ObjRef.of(heap, address, redaction);
        List<String> roots = s.rootTypes(address).stream().map(GcRoot.Type::name).toList();
        long dom = s.immediateDominator(address);
        ObjRef domRef = dom == Snapshot.ROOT ? null : ObjRef.shallow(heap, dom, redaction);

        List<Field> fields = null;
        if (withFields && (o.kind() == ObjectKind.INSTANCE || o.kind() == ObjectKind.CLASS)) {
            fields = new ArrayList<>();
            int refs = 0;
            for (FieldValue f : o.fields()) {
                Object v = f.value();
                if (f.isReference()) {
                    long a = f.address();
                    v = a == Addresses.NULL ? null : (refs++ < MAX_FIELD_REFS ? ObjRef.of(heap, a, redaction) : Addresses.hex(a));
                }
                fields.add(new Field(f.name(), f.type().name().toLowerCase(), v));
            }
        }

        Integer length = null;
        String elementType = null;
        Page<Object> elements = null;
        if (o.kind() == ObjectKind.OBJECT_ARRAY || o.kind() == ObjectKind.PRIMITIVE_ARRAY) {
            length = o.length();
            int from = Math.min(o.length(), limits.offset());
            int count = Math.min(o.length() - from, limits.limit());
            List<Object> items = new ArrayList<>(count);
            if (o.kind() == ObjectKind.OBJECT_ARRAY) {
                elementType = "object";
                for (long a : o.readReferences(from, count)) {
                    items.add(a == Addresses.NULL ? null : ObjRef.shallow(heap, a, redaction));
                }
            } else {
                elementType = o.elementType().name().toLowerCase();
                boolean textual = o.className().equals("char[]") || o.className().equals("byte[]");
                if (textual && !redaction.showValues()) {
                    items.add(new Redaction.Redacted(o.length()));
                } else {
                    Object arr = o.readPrimitives(from, count);
                    for (int i = 0; i < Array.getLength(arr); i++) {
                        Object v = Array.get(arr, i);
                        items.add(v instanceof Character c ? String.valueOf(c) : v);
                    }
                }
            }
            elements = new Page<>(items, o.length(), from, limits.limit());
        }
        return new Result(self, o.kind().name().toLowerCase(), roots, domRef, fields, length, elementType, elements);
    }
}
