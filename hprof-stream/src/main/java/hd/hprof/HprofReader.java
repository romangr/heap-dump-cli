package hd.hprof;

import hd.engine.api.Cancel;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;

import static hd.hprof.HprofTags.*;

/**
 * Single sequential pass over an HPROF file ({@code .hprof} or gzip-compressed {@code .hprof.gz}), dispatching
 * records to a {@link HprofVisitor}. Memory use is constant apart from what the visitor keeps.
 */
public final class HprofReader {

    private static final int BUFFER = 1 << 20;

    private final Path file;

    public HprofReader(Path file) {
        this.file = file;
    }

    /** Progress source: bytes of the underlying file consumed (compressed bytes for .gz). */
    public interface FilePosition extends LongSupplier {}

    public static boolean isGzip(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.read() == 0x1f && in.read() == 0x8b;
        }
    }

    /**
     * Reads the whole file.
     *
     * @param filePosition receives a supplier of consumed file bytes for progress reporting
     */
    public void read(HprofVisitor v, Cancel cancel, java.util.function.Consumer<FilePosition> filePosition) throws IOException {
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        boolean gz = isGzip(file);
        if (filePosition != null) {
            filePosition.accept(() -> {
                try {
                    return channel.position();
                } catch (IOException e) {
                    return -1;
                }
            });
        }
        InputStream raw = Channels.newInputStream(channel);
        InputStream in = gz ? new GZIPInputStream(new BufferedInputStream(raw, BUFFER), BUFFER) : raw;
        try (HprofInput input = new HprofInput(in, BUFFER)) {
            read(input, v, cancel);
        }
    }

    private void read(HprofInput in, HprofVisitor v, Cancel cancel) throws IOException {
        String format = in.cString(64);
        if (!format.startsWith("JAVA PROFILE 1.0")) {
            throw new HprofFormatException("not an HPROF heap dump: header '" + format + "'");
        }
        int idSize = in.i4();
        in.idSize(idSize);
        long timestamp = in.i8();
        v.header(format, idSize, timestamp);

        long records = 0;
        while (in.hasMore()) {
            if ((++records & 0xfff) == 0) {
                cancel.check();
                v.position(in.position());
            }
            int tag = in.u1();
            in.u4(); // time delta
            long length = in.u4();
            switch (tag) {
                case UTF8 -> {
                    long id = in.id();
                    v.utf8(id, in.bytes((int) (length - idSize)));
                }
                case LOAD_CLASS -> v.loadClass(in.i4(), in.id(), in.i4(), in.id());
                case STACK_FRAME -> v.stackFrame(in.id(), in.id(), in.id(), in.id(), in.i4(), in.i4());
                case STACK_TRACE -> {
                    int serial = in.i4();
                    int thread = in.i4();
                    int n = in.i4();
                    long[] frames = new long[n];
                    for (int i = 0; i < n; i++) {
                        frames[i] = in.id();
                    }
                    v.stackTrace(serial, thread, frames);
                }
                case HEAP_DUMP, HEAP_DUMP_SEGMENT -> heapDump(in, v, cancel, in.position() + length);
                default -> in.skip(length);
            }
        }
        v.position(in.position());
    }

    private void heapDump(HprofInput in, HprofVisitor v, Cancel cancel, long end) throws IOException {
        int idSize = in.idSize();
        long n = 0;
        while (in.position() < end) {
            if ((++n & 0xffff) == 0) {
                cancel.check();
                v.position(in.position());
            }
            int sub = in.u1();
            switch (sub) {
                case ROOT_UNKNOWN, ROOT_STICKY_CLASS, ROOT_MONITOR_USED -> v.gcRoot(sub, in.id(), 0);
                case ROOT_JNI_GLOBAL -> {
                    long id = in.id();
                    in.id(); // JNI global ref id
                    v.gcRoot(sub, id, 0);
                }
                case ROOT_JNI_LOCAL, ROOT_JAVA_FRAME -> {
                    long id = in.id();
                    int thread = in.i4();
                    in.i4(); // frame number
                    v.gcRoot(sub, id, thread);
                }
                case ROOT_NATIVE_STACK, ROOT_THREAD_BLOCK -> {
                    long id = in.id();
                    v.gcRoot(sub, id, in.i4());
                }
                case ROOT_THREAD_OBJECT -> {
                    long id = in.id();
                    int thread = in.i4();
                    int trace = in.i4();
                    v.gcRoot(sub, id, thread);
                    v.threadObject(id, thread, trace);
                }
                case CLASS_DUMP -> classDump(in, v, idSize);
                case INSTANCE_DUMP -> {
                    long id = in.id();
                    in.i4(); // stack trace serial
                    long classId = in.id();
                    int bytes = in.i4();
                    if (v.wantsInstanceData(classId)) {
                        v.instanceData(id, classId, in.bytes(bytes));
                    } else {
                        in.skip(bytes);
                        v.instance(id, classId, bytes);
                    }
                }
                case OBJ_ARRAY_DUMP -> {
                    long id = in.id();
                    in.i4();
                    int length = in.i4();
                    long arrayClass = in.id();
                    in.skip((long) length * idSize);
                    v.objectArray(id, arrayClass, length);
                }
                case PRIM_ARRAY_DUMP -> {
                    long id = in.id();
                    in.i4();
                    int length = in.i4();
                    int type = in.u1();
                    in.skip((long) length * HprofTags.size(type, idSize));
                    v.primitiveArray(id, type, length);
                }
                default -> throw new HprofFormatException(String.format(
                        "unsupported heap dump sub-record 0x%02x at offset %d (Android or non-HotSpot dump?)", sub, in.position() - 1));
            }
        }
    }

    private void classDump(HprofInput in, HprofVisitor v, int idSize) throws IOException {
        long classId = in.id();
        in.i4(); // stack trace serial
        long superId = in.id();
        long loaderId = in.id();
        in.id(); // signers
        in.id(); // protection domain
        in.id(); // reserved
        in.id(); // reserved
        int instanceSize = in.i4();
        int cp = in.u2();
        for (int i = 0; i < cp; i++) {
            in.u2();
            int type = in.u1();
            in.skip(HprofTags.size(type, idSize));
        }
        int statics = in.u2();
        for (int i = 0; i < statics; i++) {
            in.id();
            int type = in.u1();
            in.skip(HprofTags.size(type, idSize));
        }
        int fields = in.u2();
        long[] names = new long[fields];
        byte[] types = new byte[fields];
        for (int i = 0; i < fields; i++) {
            names[i] = in.id();
            types[i] = (byte) in.u1();
        }
        v.classDump(classId, superId, loaderId, instanceSize, names, types);
    }
}
