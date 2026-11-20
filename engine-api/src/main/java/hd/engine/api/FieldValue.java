package hd.engine.api;

/**
 * @param value boxed primitive, or a {@link Long} address for references ({@link Addresses#NULL} for null)
 */
public record FieldValue(String name, FieldType type, Object value) {

    public boolean isReference() {
        return type == FieldType.OBJECT;
    }

    public long address() {
        if (!isReference()) {
            throw new IllegalStateException(name + " is not a reference field");
        }
        return (Long) value;
    }
}
