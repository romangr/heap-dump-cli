package hd.engine.mat;

import hd.engine.api.Addresses;
import hd.engine.api.FieldType;
import hd.engine.api.FieldValue;
import hd.engine.api.ObjectKind;
import hd.engine.api.ObjectView;
import org.eclipse.mat.snapshot.model.Field;
import org.eclipse.mat.snapshot.model.IClass;
import org.eclipse.mat.snapshot.model.IInstance;
import org.eclipse.mat.snapshot.model.IObject;
import org.eclipse.mat.snapshot.model.IObjectArray;
import org.eclipse.mat.snapshot.model.IPrimitiveArray;
import org.eclipse.mat.snapshot.model.ObjectReference;

import java.util.ArrayList;
import java.util.List;

final class MatObjectView implements ObjectView {

    private final MatSnapshot snapshot;
    private final IObject o;
    private List<FieldValue> fields;

    MatObjectView(MatSnapshot snapshot, IObject o) {
        this.snapshot = snapshot;
        this.o = o;
    }

    @Override
    public long address() {
        return o.getObjectAddress();
    }

    @Override
    public ObjectKind kind() {
        if (o instanceof IClass) {
            return ObjectKind.CLASS;
        }
        if (o instanceof IObjectArray) {
            return ObjectKind.OBJECT_ARRAY;
        }
        if (o instanceof IPrimitiveArray) {
            return ObjectKind.PRIMITIVE_ARRAY;
        }
        return ObjectKind.INSTANCE;
    }

    @Override
    public long classAddress() {
        return o.getClazz().getObjectAddress();
    }

    @Override
    public String className() {
        return o.getClazz().getName();
    }

    @Override
    public long shallowSize() {
        return o.getUsedHeapSize();
    }

    @Override
    public int length() {
        if (o instanceof IObjectArray a) {
            return a.getLength();
        }
        if (o instanceof IPrimitiveArray a) {
            return a.getLength();
        }
        return -1;
    }

    @Override
    public synchronized List<FieldValue> fields() {
        if (fields == null) {
            List<Field> raw;
            if (o instanceof IClass c) {
                raw = c.getStaticFields();
            } else if (o instanceof IInstance i) {
                raw = i.getFields();
            } else {
                raw = List.of();
            }
            List<FieldValue> out = new ArrayList<>(raw.size());
            for (Field f : raw) {
                FieldType type = MatSnapshot.fieldType(f.getType());
                Object v = f.getValue();
                if (type == FieldType.OBJECT) {
                    v = v instanceof ObjectReference ref ? ref.getObjectAddress() : Addresses.NULL;
                }
                out.add(new FieldValue(f.getName(), type, v));
            }
            fields = List.copyOf(out);
        }
        return fields;
    }

    @Override
    public FieldType elementType() {
        return o instanceof IPrimitiveArray a ? MatSnapshot.fieldType(a.getType()) : null;
    }

    @Override
    public Object readPrimitives(int offset, int count) {
        if (!(o instanceof IPrimitiveArray a)) {
            throw new IllegalStateException(Addresses.hex(address()) + " is not a primitive array");
        }
        int len = clamp(offset, count, a.getLength());
        return a.getValueArray(offset, len);
    }

    @Override
    public long[] readReferences(int offset, int count) {
        if (!(o instanceof IObjectArray a)) {
            throw new IllegalStateException(Addresses.hex(address()) + " is not an object array");
        }
        int len = clamp(offset, count, a.getLength());
        return a.getReferenceArray(offset, len);
    }

    private static int clamp(int offset, int count, int length) {
        if (offset < 0 || offset > length) {
            throw new IndexOutOfBoundsException("offset " + offset + " outside array of length " + length);
        }
        return Math.max(0, Math.min(count, length - offset));
    }
}
