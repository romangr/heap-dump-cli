package hd.analysis.detect;

import hd.analysis.Size;

import java.util.List;

/**
 * One problem found by a detector.
 *
 * @param evidence addresses (at most a handful) that show the problem, largest first
 * @param next suggested follow-up commands; "{session}" is replaced with the session id by the daemon
 */
public record Finding(String detector, Severity severity, String title, String explanation, Evidence evidence,
                      List<String> next) {

    public enum Severity { HIGH, MEDIUM, LOW, INFO }

    /**
     * @param addrs sample addresses, largest first
     * @param count how many objects/instances the finding covers
     * @param size the memory involved (retained, wasted or off-heap depending on the detector)
     */
    public record Evidence(List<String> addrs, long count, Size size) {}

    /** Severity from the share of the heap involved. */
    public static Severity bySize(long bytes, long heap) {
        double share = heap > 0 ? (double) bytes / heap : 0;
        if (share >= 0.20) return Severity.HIGH;
        if (share >= 0.05) return Severity.MEDIUM;
        if (share >= 0.01) return Severity.LOW;
        return Severity.INFO;
    }
}
