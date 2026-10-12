/**
 * Streaming parser for text with one integer per line.
 *
 * Bytes can be fed in pieces of any size: a number that is split between two
 * pieces is continued correctly, because the parser keeps its state (sign,
 * digits seen so far) between calls to feed(). Call finish() at the end so the
 * last number is counted even if the text does not end with a newline.
 * Accepts both "\n" and "\r\n" line endings.
 */
public final class SignParser {
    private final Counts counts = new Counts();
    private boolean negative;
    private boolean hasDigits;
    private int value;

    public void feed(byte[] buf, int from, int to) {
        for (int i = from; i < to; i++) {
            byte b = buf[i];
            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
                hasDigits = true;
            } else if (b == '\n') {
                endOfLine();
            } else if (b == '-') {
                negative = true;
            }
            // '\r', '+' and spaces are ignored
        }
    }

    public Counts finish() {
        endOfLine();
        return counts;
    }

    private void endOfLine() {
        if (hasDigits) {
            counts.add(negative ? -value : value);
        }
        negative = false;
        hasDigits = false;
        value = 0;
    }
}
