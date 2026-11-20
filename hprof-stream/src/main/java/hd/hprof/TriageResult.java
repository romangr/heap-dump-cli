package hd.hprof;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Result of {@link Triage}. Sizes are estimates (see {@link #compressedOops}); retained sizes are not available.
 *
 * @param compressedOops the oops assumption used for size estimates
 * @param compressedOopsGuessed whether that assumption was guessed rather than given
 * @param objects number of objects, arrays and classes in the dump, including objects not reachable from GC roots
 *                (a full open discards those, so its counts can be lower)
 * @param estimatedHeapBytes sum of estimated shallow sizes of instances and arrays
 * @param classes per class (instances, object arrays per array class, primitive arrays per type), largest first
 */
public record TriageResult(Path file, long fileBytes, boolean gzip, String format, int identifierSize,
                           long timestampMillis, boolean compressedOops, boolean compressedOopsGuessed,
                           long objects, long estimatedHeapBytes, List<ClassRow> classes, List<ArrayRow> biggestArrays,
                           Strings strings, Map<String, Long> gcRoots, List<ThreadRow> threads, long elapsedMillis) {

    public record ClassRow(String className, long count, long estimatedBytes) {}

    public record ArrayRow(long address, String type, int length, long estimatedBytes) {}

    /** java.lang.String instances; their backing byte[]/char[] are counted in those rows. */
    public record Strings(long count, long estimatedBytes) {}

    public record ThreadRow(long address, int serial, List<FrameRow> frames) {}

    public record FrameRow(String method, String location) {}
}
