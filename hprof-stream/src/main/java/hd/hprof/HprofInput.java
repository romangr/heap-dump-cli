package hd.hprof;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Big-endian reader over a buffered stream with a large buffer, tracking the logical position.
 * Not thread-safe. Kept minimal and branch-light: it is the hot loop of a 30+ GB scan.
 */
final class HprofInput implements AutoCloseable {

    private final InputStream in;
    private final byte[] buf;
    private int pos;
    private int limit;
    private long consumed; // bytes before buf[0]
    private int idSize = 8;

    HprofInput(InputStream in, int bufferSize) {
        this.in = in;
        this.buf = new byte[bufferSize];
    }

    void idSize(int size) {
        if (size != 4 && size != 8) {
            throw new HprofFormatException("unsupported identifier size " + size);
        }
        this.idSize = size;
    }

    int idSize() {
        return idSize;
    }

    /** Logical position in the (uncompressed) stream. */
    long position() {
        return consumed + pos;
    }

    /** @return false at clean end of stream */
    boolean hasMore() throws IOException {
        return pos < limit || fill(1);
    }

    private boolean fill(int need) throws IOException {
        int remaining = limit - pos;
        if (remaining > 0 && pos > 0) {
            System.arraycopy(buf, pos, buf, 0, remaining);
        }
        consumed += pos;
        pos = 0;
        limit = remaining;
        while (limit < need) {
            int n = in.read(buf, limit, buf.length - limit);
            if (n < 0) {
                return limit >= need;
            }
            limit += n;
        }
        return true;
    }

    private void require(int n) throws IOException {
        if (limit - pos < n && !fill(n)) {
            throw new EOFException("unexpected end of dump at " + position());
        }
    }

    int u1() throws IOException {
        require(1);
        return buf[pos++] & 0xff;
    }

    int u2() throws IOException {
        require(2);
        int v = ((buf[pos] & 0xff) << 8) | (buf[pos + 1] & 0xff);
        pos += 2;
        return v;
    }

    int i4() throws IOException {
        require(4);
        int v = ((buf[pos] & 0xff) << 24) | ((buf[pos + 1] & 0xff) << 16) | ((buf[pos + 2] & 0xff) << 8) | (buf[pos + 3] & 0xff);
        pos += 4;
        return v;
    }

    long u4() throws IOException {
        return i4() & 0xffffffffL;
    }

    long i8() throws IOException {
        require(8);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (buf[pos + i] & 0xff);
        }
        pos += 8;
        return v;
    }

    long id() throws IOException {
        return idSize == 8 ? i8() : u4();
    }

    byte[] bytes(int n) throws IOException {
        byte[] out = new byte[n];
        int off = 0;
        while (off < n) {
            if (pos >= limit && !fill(1)) {
                throw new EOFException("unexpected end of dump at " + position());
            }
            int chunk = Math.min(n - off, limit - pos);
            System.arraycopy(buf, pos, out, off, chunk);
            pos += chunk;
            off += chunk;
        }
        return out;
    }

    void skip(long n) throws IOException {
        long available = limit - pos;
        if (n <= available) {
            pos += (int) n;
            return;
        }
        n -= available;
        consumed += limit;
        pos = 0;
        limit = 0;
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                // skip() may return 0 on some streams (e.g. GZIP at buffer edges): fall back to reading
                int r = in.read(buf, 0, (int) Math.min(buf.length, n));
                if (r < 0) {
                    throw new EOFException("unexpected end of dump while skipping");
                }
                s = r;
            }
            consumed += s;
            n -= s;
        }
    }

    /** Reads a C string up to and including the NUL terminator (header only). */
    String cString(int max) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < max; i++) {
            int c = u1();
            if (c == 0) {
                return sb.toString();
            }
            sb.append((char) c);
        }
        throw new HprofFormatException("not an HPROF file (no header terminator)");
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
