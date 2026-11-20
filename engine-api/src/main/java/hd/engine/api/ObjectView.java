package hd.engine.api;

import java.util.List;
import java.util.Optional;

/**
 * Read access to one object. Field and element reads are lazy so large arrays are never materialized whole.
 */
public interface ObjectView {

    long address();

    ObjectKind kind();

    long classAddress();

    String className();

    long shallowSize();

    /** Array length, or -1 for non-arrays. */
    int length();

    /** Instance fields (most specific class first, then superclasses). For a CLASS object: its static fields. */
    List<FieldValue> fields();

    /** First field with this name, searching the most specific class first. */
    default Optional<FieldValue> field(String name) {
        for (FieldValue f : fields()) {
            if (f.name().equals(name)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /** Address held in a reference field, or {@link Addresses#NULL}. */
    default long refField(String name) {
        return field(name).filter(FieldValue::isReference).map(f -> (Long) f.value()).orElse(Addresses.NULL);
    }

    /** Element type of a primitive array, e.g. BYTE; null for other kinds. */
    FieldType elementType();

    /**
     * Reads elements of a primitive array as the matching Java array type (byte[], char[], int[], …).
     * @throws IllegalStateException if this is not a primitive array
     */
    Object readPrimitives(int offset, int count);

    /**
     * Reads element addresses of an object array ({@link Addresses#NULL} for null elements).
     * @throws IllegalStateException if this is not an object array
     */
    long[] readReferences(int offset, int count);
}
