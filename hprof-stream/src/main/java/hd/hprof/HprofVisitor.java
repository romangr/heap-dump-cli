package hd.hprof;

/**
 * Callbacks for a single sequential pass over an HPROF file. Default methods ignore the record.
 * Payloads not requested by a visitor are skipped without being copied.
 */
public interface HprofVisitor {

    default void header(String format, int idSize, long timestampMillis) {}

    default void utf8(long id, byte[] modifiedUtf8) {}

    default void loadClass(int classSerial, long classId, int stackSerial, long nameId) {}

    default void stackFrame(long frameId, long methodNameId, long signatureId, long sourceFileId, int classSerial, int line) {}

    default void stackTrace(int serial, int threadSerial, long[] frameIds) {}

    /** @param type one of the ROOT_* sub-record tags, see {@link HprofTags} */
    default void gcRoot(int type, long objectId, int threadSerial) {}

    default void threadObject(long threadId, int threadSerial, int stackTraceSerial) {}

    /**
     * @param instanceFields declared instance fields of this class only: name ids and basic types
     */
    default void classDump(long classId, long superId, long loaderId, int instanceSize,
                           long[] instanceFieldNameIds, byte[] instanceFieldTypes) {}

    /** @param fieldBytes size of the instance field data in the dump */
    default void instance(long objectId, long classId, int fieldBytes) {}

    /** Called instead of {@link #instance} when {@link #wantsInstanceData(long)} returned true. */
    default void instanceData(long objectId, long classId, byte[] fieldData) {}

    /** Whether the field data of this class's instances should be delivered (default: no, it is skipped). */
    default boolean wantsInstanceData(long classId) {
        return false;
    }

    default void objectArray(long arrayId, long arrayClassId, int length) {}

    /** @param elementType basic type tag (4=boolean … 11=long) */
    default void primitiveArray(long arrayId, int elementType, int length) {}

    /** Periodic callback with the number of (uncompressed) bytes read so far, for progress reporting. */
    default void position(long uncompressedPosition) {}
}
