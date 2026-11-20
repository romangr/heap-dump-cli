package hd.hprof;

/** The file is not a valid (or not a supported) HPROF dump. */
public class HprofFormatException extends RuntimeException {
    public HprofFormatException(String message) {
        super(message);
    }
}
