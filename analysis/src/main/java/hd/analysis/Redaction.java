package hd.analysis;

/**
 * Whether string and char/byte array contents may be shown. Off by default: dumps contain secrets and personal data.
 * Class, field and thread names and primitive field values are always shown.
 */
public record Redaction(boolean showValues) {

    public static final Redaction ON = new Redaction(false);
    public static final Redaction OFF = new Redaction(true);

    /** Value placeholder for redacted content. */
    public record Redacted(int len, boolean redacted) {
        public Redacted(int len) {
            this(len, true);
        }
    }
}
