package hd.engine.api;

import java.util.List;

/**
 * @param superclassAddress {@link Addresses#NULL} for java.lang.Object and primitives
 * @param loaderAddress {@link Addresses#NULL} for the bootstrap loader
 * @param instanceSize shallow size of one instance; -1 for array classes (size varies)
 * @param instanceCount number of instances of exactly this class
 * @param array whether this is an array class
 * @param fields declared instance fields of this class only (not inherited)
 */
public record ClassView(long address, String name, long superclassAddress, long loaderAddress, long instanceSize,
                        long instanceCount, boolean array, List<FieldDecl> fields) {

    public record FieldDecl(String name, FieldType type) {}
}
