import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Cuts the byte stream of a TCP connection into messages.
 *
 * TCP delivers a stream of bytes, not messages: one send() of the other side
 * can arrive in several receive() calls, and several sends can arrive in one.
 * Our protocol therefore ends every message with '\n'. This class reads the
 * bytes into a buffer and returns one message per '\n':
 *   - a message that arrives in two pieces stays in `line` until its '\n' comes;
 *   - two messages that arrive in one receive are returned by two calls.
 * A message longer than maxLength is not kept in memory: readLine() throws
 * LineTooLongException at once and the rest, up to the next '\n', is skipped.
 */
public final class LineReader {

    /** Thrown when a message is longer than the limit. The connection stays usable. */
    public static final class LineTooLongException extends IOException {
        LineTooLongException(int max) {
            super("message longer than " + max + " bytes");
        }
    }

    private final InputStream in;
    private final int maxLength;
    private final byte[] buffer = new byte[4096];
    private int pos;            // next unread byte in buffer
    private int len;            // number of valid bytes in buffer
    private boolean skipping;   // inside a message that was too long
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    public LineReader(InputStream in, int maxLength) {
        this.in = in;
        this.maxLength = maxLength;
    }

    /**
     * Returns the next message without its '\n' (and without a '\r' before it),
     * or null when the other side has closed the connection (receive returned -1).
     */
    public String readLine() throws IOException {
        while (true) {
            if (pos == len) {                       // buffer used up: receive more bytes
                len = in.read(buffer);              // blocks until bytes arrive
                pos = 0;
                if (len == -1) {                    // the other side closed the connection
                    len = 0;
                    return null;
                }
            }
            byte b = buffer[pos++];
            if (b == '\n') {
                if (skipping) {                     // end of the too-long message
                    skipping = false;
                    line.reset();
                    continue;
                }
                String text = line.toString(StandardCharsets.UTF_8);
                line.reset();
                if (text.endsWith("\r")) text = text.substring(0, text.length() - 1);
                return text;
            }
            if (skipping) continue;
            if (line.size() == maxLength) {         // too long: report it once, then skip the rest
                skipping = true;
                line.reset();
                throw new LineTooLongException(maxLength);
            }
            line.write(b);
        }
    }
}
