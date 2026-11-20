package hd.hprof;

/** Name conversions shared by the streaming reader's consumers. */
public final class TriageNames {

    private TriageNames() {}

    /** "java/lang/String" → "java.lang.String", "[Ljava/lang/Object;" → "java.lang.Object[]", "[[I" → "int[][]". */
    public static String javaName(String internal) {
        int dims = 0;
        while (dims < internal.length() && internal.charAt(dims) == '[') {
            dims++;
        }
        String base = internal.substring(dims);
        if (dims > 0) {
            base = switch (base) {
                case "Z" -> "boolean";
                case "C" -> "char";
                case "F" -> "float";
                case "D" -> "double";
                case "B" -> "byte";
                case "S" -> "short";
                case "I" -> "int";
                case "J" -> "long";
                default -> base.startsWith("L") && base.endsWith(";") ? base.substring(1, base.length() - 1) : base;
            };
        }
        return base.replace('/', '.') + "[]".repeat(dims);
    }
}
