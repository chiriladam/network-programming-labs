import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * The log file of one peer (chat-<name>.log): one line for every datagram
 * that it sends, receives or drops, with the time, the endpoint, the kind of
 * message and the size.
 */
public final class ChatLog {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private final PrintWriter out;

    public ChatLog(String file) throws IOException {
        out = new PrintWriter(new FileWriter(file, true), true);
        out.println("---- started " + java.time.LocalDateTime.now());
    }

    public synchronized void sent(InetSocketAddress to, String kind, int size) {
        line("SENT", to, kind, size, "");
    }

    public synchronized void received(InetSocketAddress from, String kind, int size) {
        line("RECV", from, kind, size, "");
    }

    public synchronized void dropped(InetSocketAddress from, String reason, int size) {
        line("DROP", from, "-", size, reason);
    }

    public synchronized void note(String text) {
        out.println(LocalTime.now().format(TIME) + "  NOTE  " + text);
    }

    private void line(String what, InetSocketAddress ep, String kind, int size, String extra) {
        String endpoint = ep.getAddress().getHostAddress() + ":" + ep.getPort();
        out.printf("%s  %s  %-22s %-7s %5d bytes  %s%n",
                LocalTime.now().format(TIME), what, endpoint, kind, size, extra);
    }

    public synchronized void close() {
        out.close();
    }
}
