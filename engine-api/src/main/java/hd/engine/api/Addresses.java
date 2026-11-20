package hd.engine.api;

/** Heap addresses are the only object identity across the boundary. Rendered as "0x…" for humans and agents. */
public final class Addresses {

    /** Sentinel for "no object" (null reference). */
    public static final long NULL = 0L;

    private Addresses() {}

    public static String hex(long address) {
        return "0x" + Long.toHexString(address);
    }

    /** Parses "0x7f3a10c8", "7f3a10c8" or a decimal with a leading "#". */
    public static long parse(String text) {
        String t = text.trim();
        try {
            if (t.startsWith("#")) {
                return Long.parseLong(t.substring(1));
            }
            if (t.startsWith("0x") || t.startsWith("0X")) {
                t = t.substring(2);
            }
            return Long.parseUnsignedLong(t, 16);
        } catch (NumberFormatException e) {
            throw new EngineException(EngineException.Code.INVALID_ARGUMENT, "not an address: " + text);
        }
    }
}
