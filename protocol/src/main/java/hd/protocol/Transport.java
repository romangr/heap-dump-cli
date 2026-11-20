package hd.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** One request, one response per connection: a JSON document terminated by a newline in each direction. */
public final class Transport {

    private Transport() {}

    public static Response call(Path socket, Request request) throws IOException {
        try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            ch.connect(UnixDomainSocketAddress.of(socket));
            writeLine(ch, Json.MAPPER.writeValueAsBytes(request));
            byte[] reply = readLine(ch);
            if (reply == null) {
                throw new IOException("daemon closed the connection without a reply (it may have run out of memory)");
            }
            return Json.MAPPER.readValue(reply, Response.class);
        }
    }

    public static void writeLine(SocketChannel ch, byte[] json) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(json.length + 1);
        buf.put(json).put((byte) '\n').flip();
        while (buf.hasRemaining()) {
            ch.write(buf);
        }
    }

    /** @return the line without terminator, or null if the peer closed before sending anything */
    public static byte[] readLine(SocketChannel ch) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer buf = ByteBuffer.allocate(64 * 1024);
        while (true) {
            buf.clear();
            int n = ch.read(buf);
            if (n < 0) {
                return out.size() == 0 ? null : out.toByteArray();
            }
            for (int i = 0; i < n; i++) {
                byte b = buf.get(i);
                if (b == '\n') {
                    return out.toByteArray();
                }
                out.write(b);
            }
        }
    }

    public static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
