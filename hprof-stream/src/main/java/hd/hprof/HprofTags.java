package hd.hprof;

/** HPROF record and sub-record tags and basic type codes (HotSpot heap dump format 1.0.1 / 1.0.2). */
public final class HprofTags {

    private HprofTags() {}

    // top-level records
    public static final int UTF8 = 0x01;
    public static final int LOAD_CLASS = 0x02;
    public static final int STACK_FRAME = 0x04;
    public static final int STACK_TRACE = 0x05;
    public static final int HEAP_DUMP = 0x0C;
    public static final int HEAP_DUMP_SEGMENT = 0x1C;
    public static final int HEAP_DUMP_END = 0x2C;

    // heap dump sub-records
    public static final int ROOT_UNKNOWN = 0xFF;
    public static final int ROOT_JNI_GLOBAL = 0x01;
    public static final int ROOT_JNI_LOCAL = 0x02;
    public static final int ROOT_JAVA_FRAME = 0x03;
    public static final int ROOT_NATIVE_STACK = 0x04;
    public static final int ROOT_STICKY_CLASS = 0x05;
    public static final int ROOT_THREAD_BLOCK = 0x06;
    public static final int ROOT_MONITOR_USED = 0x07;
    public static final int ROOT_THREAD_OBJECT = 0x08;
    public static final int CLASS_DUMP = 0x20;
    public static final int INSTANCE_DUMP = 0x21;
    public static final int OBJ_ARRAY_DUMP = 0x22;
    public static final int PRIM_ARRAY_DUMP = 0x23;

    // basic types
    public static final int T_OBJECT = 2;
    public static final int T_BOOLEAN = 4;
    public static final int T_CHAR = 5;
    public static final int T_FLOAT = 6;
    public static final int T_DOUBLE = 7;
    public static final int T_BYTE = 8;
    public static final int T_SHORT = 9;
    public static final int T_INT = 10;
    public static final int T_LONG = 11;

    /** Size in the dump of a value of this basic type (object = identifier size). */
    public static int size(int type, int idSize) {
        return switch (type) {
            case T_OBJECT -> idSize;
            case T_BOOLEAN, T_BYTE -> 1;
            case T_CHAR, T_SHORT -> 2;
            case T_FLOAT, T_INT -> 4;
            case T_DOUBLE, T_LONG -> 8;
            default -> throw new HprofFormatException("unknown basic type " + type);
        };
    }

    public static String primitiveArrayName(int type) {
        return switch (type) {
            case T_BOOLEAN -> "boolean[]";
            case T_CHAR -> "char[]";
            case T_FLOAT -> "float[]";
            case T_DOUBLE -> "double[]";
            case T_BYTE -> "byte[]";
            case T_SHORT -> "short[]";
            case T_INT -> "int[]";
            case T_LONG -> "long[]";
            default -> throw new HprofFormatException("unknown primitive array type " + type);
        };
    }

    public static String rootName(int tag) {
        return switch (tag) {
            case ROOT_UNKNOWN -> "UNKNOWN";
            case ROOT_JNI_GLOBAL -> "JNI_GLOBAL";
            case ROOT_JNI_LOCAL -> "JNI_LOCAL";
            case ROOT_JAVA_FRAME -> "JAVA_LOCAL";
            case ROOT_NATIVE_STACK -> "NATIVE_STACK";
            case ROOT_STICKY_CLASS -> "SYSTEM_CLASS";
            case ROOT_THREAD_BLOCK -> "THREAD_BLOCK";
            case ROOT_MONITOR_USED -> "BUSY_MONITOR";
            case ROOT_THREAD_OBJECT -> "THREAD_OBJECT";
            default -> "UNKNOWN";
        };
    }
}
