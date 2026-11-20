package hd.analysis.detect;

import java.util.List;

/**
 * A heuristic that looks for one kind of memory problem. Implementations are registered in
 * META-INF/services/hd.analysis.detect.Detector and must only use the engine API (through {@link DetectContext}).
 */
public interface Detector {

    /** Stable id used on the command line, e.g. "threadlocal". */
    String id();

    /** One-line description for help output. */
    String description();

    /** Whether it runs by default; expensive detectors (full scans of all strings etc.) are opt-in. */
    boolean defaultEnabled();

    List<Finding> run(DetectContext ctx);
}
