package hd.hprof;

import hd.engine.api.Cancel;
import hd.engine.api.Progress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;

import static hd.hprof.HprofTags.*;

/**
 * One streaming pass producing a first look at a dump without indexing: per-class counts and estimated shallow
 * sizes, biggest arrays, string totals, GC root counts and thread stacks. Retained sizes are not available.
 *
 * Shallow sizes are estimates following HotSpot's layout (header + fields, 8-byte alignment); whether compressed
 * oops were used is not recorded in HPROF, so it is assumed (see {@link Options#compressedOops}).
 */
public final class Triage {

    /**
     * @param topArrays how many of the biggest arrays to keep
     * @param compressedOops null = guess (true unless the dump is larger than 32 GiB uncompressed)
     */
    public record Options(int topArrays, Boolean compressedOops) {
        public static Options defaults() {
            return new Options(20, null);
        }
    }

    private Triage() {}

    public static TriageResult run(Path file, Options options, Progress progress, Cancel cancel) throws IOException {
        long fileSize = Files.size(file);
        boolean gz = HprofReader.isGzip(file);
        boolean compressedGuess = options.compressedOops() != null ? options.compressedOops()
                : gz || fileSize < 32L * 1024 * 1024 * 1024;
        Collector c = new Collector(options.topArrays(), compressedGuess);
        HprofReader.FilePosition[] pos = new HprofReader.FilePosition[1];
        long started = System.nanoTime();
        c.onPosition = p -> {
            long filePos = pos[0] == null ? p : pos[0].getAsLong();
            progress.update(gz ? "scanning (gzip)" : "scanning", fileSize > 0 ? Math.min(1.0, (double) filePos / fileSize) : -1);
        };
        new HprofReader(file).read(c, cancel, p -> pos[0] = p);
        progress.update("done", 1.0);
        return c.result(file, fileSize, gz, options.compressedOops() == null, (System.nanoTime() - started) / 1_000_000);
    }

    // =====================================================================================================

    private static final class Collector implements HprofVisitor {

        final int topArrays;
        final boolean compressed;
        int idSize;
        String format;
        long timestamp;
        java.util.function.LongConsumer onPosition = p -> {};

        final Map<Long, String> utf8 = new HashMap<>();
        final Map<Long, Long> classNameIds = new HashMap<>();      // classId → name UTF8 id
        final Map<Integer, Long> classBySerial = new HashMap<>();  // class serial → classId
        final Map<Long, ClassInfo> classInfo = new HashMap<>();    // classId → layout
        final Map<Long, long[]> instanceStats = new HashMap<>();   // classId → [count]
        final Map<Long, long[]> objArrayStats = new HashMap<>();   // arrayClassId → [count, bytes]
        final long[][] primStats = new long[12][2];                // type → [count, bytes]
        final Map<String, Long> rootCounts = new TreeMap<>();
        final Map<Long, Frame> frames = new HashMap<>();
        final Map<Integer, long[]> traces = new HashMap<>();        // trace serial → frame ids
        final List<long[]> threads = new ArrayList<>();            // [threadId, threadSerial, traceSerial]
        final PriorityQueue<TriageResult.ArrayRow> biggest =
                new PriorityQueue<>(Comparator.comparingLong(TriageResult.ArrayRow::estimatedBytes));
        long objects;

        record ClassInfo(long superId, int ownFieldBytes) {}

        record Frame(long methodNameId, long sourceFileId, int classSerial, int line) {}

        Collector(int topArrays, boolean compressed) {
            this.topArrays = topArrays;
            this.compressed = compressed;
        }

        int refSize() {
            return idSize == 8 && compressed ? 4 : idSize;
        }

        int headerSize() {
            if (idSize == 4) return 8;
            return compressed ? 12 : 16;
        }

        int arrayHeaderSize() {
            if (idSize == 4) return 12;
            return compressed ? 16 : 20;
        }

        static long align(long n) {
            return (n + 7) & ~7L;
        }

        @Override
        public void header(String format, int idSize, long timestampMillis) {
            this.format = format;
            this.idSize = idSize;
            this.timestamp = timestampMillis;
        }

        @Override
        public void utf8(long id, byte[] bytes) {
            utf8.put(id, new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void loadClass(int classSerial, long classId, int stackSerial, long nameId) {
            classNameIds.put(classId, nameId);
            classBySerial.put(classSerial, classId);
        }

        @Override
        public void stackFrame(long frameId, long methodNameId, long signatureId, long sourceFileId, int classSerial, int line) {
            frames.put(frameId, new Frame(methodNameId, sourceFileId, classSerial, line));
        }

        @Override
        public void stackTrace(int serial, int threadSerial, long[] frameIds) {
            traces.put(serial, frameIds);
        }

        @Override
        public void gcRoot(int type, long objectId, int threadSerial) {
            rootCounts.merge(HprofTags.rootName(type), 1L, Long::sum);
        }

        @Override
        public void threadObject(long threadId, int threadSerial, int stackTraceSerial) {
            threads.add(new long[]{threadId, threadSerial, stackTraceSerial});
        }

        @Override
        public void classDump(long classId, long superId, long loaderId, int instanceSize, long[] names, byte[] types) {
            int bytes = 0;
            for (byte t : types) {
                bytes += t == T_OBJECT ? refSize() : HprofTags.size(t, idSize);
            }
            classInfo.put(classId, new ClassInfo(superId, bytes));
            objects++;
        }

        @Override
        public void instance(long objectId, long classId, int fieldBytes) {
            objects++;
            long[] s = instanceStats.get(classId);
            if (s == null) {
                instanceStats.put(classId, s = new long[1]);
            }
            s[0]++;
        }

        @Override
        public void objectArray(long arrayId, long arrayClassId, int length) {
            objects++;
            long bytes = align(arrayHeaderSize() + (long) length * refSize());
            long[] s = objArrayStats.get(arrayClassId);
            if (s == null) {
                objArrayStats.put(arrayClassId, s = new long[2]);
            }
            s[0]++;
            s[1] += bytes;
            offer(arrayId, arrayClassId, -1, length, bytes);
        }

        @Override
        public void primitiveArray(long arrayId, int elementType, int length) {
            objects++;
            long bytes = align(arrayHeaderSize() + (long) length * HprofTags.size(elementType, idSize));
            primStats[elementType][0]++;
            primStats[elementType][1] += bytes;
            offer(arrayId, 0, elementType, length, bytes);
        }

        private void offer(long id, long arrayClassId, int primType, int length, long bytes) {
            if (biggest.size() < topArrays || biggest.peek().estimatedBytes() < bytes) {
                // name resolved later; keep class id / prim type in the row temporarily
                String type = primType > 0 ? HprofTags.primitiveArrayName(primType) : "#" + arrayClassId;
                biggest.add(new TriageResult.ArrayRow(id, type, length, bytes));
                if (biggest.size() > topArrays) {
                    biggest.poll();
                }
            }
        }

        @Override
        public void position(long uncompressedPosition) {
            onPosition.accept(uncompressedPosition);
        }

        // ---- result

        String className(long classId) {
            Long nameId = classNameIds.get(classId);
            String raw = nameId == null ? null : utf8.get(nameId);
            return raw == null ? "<unknown class 0x" + Long.toHexString(classId) + ">" : TriageNames.javaName(raw);
        }

        long instanceSize(long classId) {
            long fields = 0;
            int guard = 0;
            for (long c = classId; c != 0 && guard++ < 1000; ) {
                ClassInfo ci = classInfo.get(c);
                if (ci == null) {
                    break;
                }
                fields += ci.ownFieldBytes();
                c = ci.superId();
            }
            return align(headerSize() + fields);
        }

        TriageResult result(Path file, long fileSize, boolean gz, boolean compressedGuessed, long elapsedMs) {
            List<TriageResult.ClassRow> rows = new ArrayList<>();
            long total = 0;
            for (var e : instanceStats.entrySet()) {
                long count = e.getValue()[0];
                long bytes = count * instanceSize(e.getKey());
                rows.add(new TriageResult.ClassRow(className(e.getKey()), count, bytes));
                total += bytes;
            }
            for (var e : objArrayStats.entrySet()) {
                rows.add(new TriageResult.ClassRow(className(e.getKey()), e.getValue()[0], e.getValue()[1]));
                total += e.getValue()[1];
            }
            for (int t = 0; t < primStats.length; t++) {
                if (primStats[t][0] > 0) {
                    rows.add(new TriageResult.ClassRow(HprofTags.primitiveArrayName(t), primStats[t][0], primStats[t][1]));
                    total += primStats[t][1];
                }
            }
            // class objects themselves: count them, size is engine-specific (statics, metadata) and left out
            rows.sort(Comparator.comparingLong(TriageResult.ClassRow::estimatedBytes).reversed());

            List<TriageResult.ArrayRow> arrays = new ArrayList<>(biggest);
            arrays.sort(Comparator.comparingLong(TriageResult.ArrayRow::estimatedBytes).reversed());
            arrays.replaceAll(a -> a.type().startsWith("#")
                    ? new TriageResult.ArrayRow(a.address(), className(Long.parseLong(a.type().substring(1))), a.length(), a.estimatedBytes())
                    : a);

            long stringClass = classNameIds.entrySet().stream()
                    .filter(e -> "java/lang/String".equals(utf8.get(e.getValue())))
                    .map(Map.Entry::getKey).findFirst().orElse(0L);
            long[] stringStats = instanceStats.getOrDefault(stringClass, new long[1]);
            TriageResult.Strings strings = new TriageResult.Strings(stringStats[0],
                    stringStats[0] * (stringClass == 0 ? 0 : instanceSize(stringClass)));

            List<TriageResult.ThreadRow> threadRows = new ArrayList<>();
            for (long[] t : threads) {
                long[] frameIds = traces.getOrDefault((int) t[2], new long[0]);
                List<TriageResult.FrameRow> fr = new ArrayList<>();
                for (long fid : frameIds) {
                    Frame f = frames.get(fid);
                    if (f == null) continue;
                    Long classId = classBySerial.get(f.classSerial());
                    String cls = classId == null ? "?" : className(classId);
                    String method = cls + "." + utf8.getOrDefault(f.methodNameId(), "?");
                    String src = utf8.get(f.sourceFileId());
                    String location = f.line() == -3 ? "Native Method"
                            : src == null ? null : f.line() > 0 ? src + ":" + f.line() : src;
                    fr.add(new TriageResult.FrameRow(method, location));
                }
                threadRows.add(new TriageResult.ThreadRow(t[0], (int) t[1], fr));
            }

            return new TriageResult(file, fileSize, gz, format, idSize, timestamp, compressed, compressedGuessed,
                    objects, total, rows, arrays, strings, rootCounts, threadRows, elapsedMs);
        }
    }
}
