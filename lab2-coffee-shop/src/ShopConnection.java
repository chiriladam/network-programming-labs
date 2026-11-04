import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * The client side of one connection, used by Client (the console program) and by Demo.
 *
 *   - a receiver thread reads the server's messages all the time and passes
 *     each one to onMessage as soon as it arrives;
 *   - a heartbeat thread sends PING every 5 s, so the server can tell a silent
 *     but alive client from one whose network is gone, and the server's PONG
 *     lets us notice when the server is gone;
 *   - send() is synchronized, because the user's thread and the heartbeat
 *     thread both write to the socket.
 */
public final class ShopConnection {

    private static final int PING_EVERY_MS = 5_000;

    private final Socket socket = new Socket();
    private OutputStream out;
    private volatile boolean closing;

    /** connect(): opens the TCP connection (3-way handshake) to host:port. */
    public void connect(String host, int port) throws IOException {
        socket.connect(new InetSocketAddress(host, port), 5_000);
        socket.setSoTimeout(ClientSession.IDLE_TIMEOUT_MS);
        out = socket.getOutputStream();
    }

    /**
     * Starts the receiver and heartbeat threads.
     * @param onMessage called with every message from the server (PONG is hidden)
     * @param onClosed  called once when the connection ends, with the reason
     */
    public void start(Consumer<String> onMessage, Consumer<String> onClosed) {
        Thread receiver = new Thread(() -> {
            String reason = "the server closed the connection";
            try {
                LineReader reader = new LineReader(socket.getInputStream(), 64 * 1024);
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.equals("PONG")) onMessage.accept(line);
                }
            } catch (SocketTimeoutException e) {
                reason = "no answer from the server for " + ClientSession.IDLE_TIMEOUT_MS / 1000 + "s";
            } catch (IOException e) {
                reason = closing ? "connection closed" : "connection lost: " + e.getMessage();
            }
            close();
            onClosed.accept(reason);
        }, "receiver");
        receiver.setDaemon(true);
        receiver.start();

        Thread heartbeat = new Thread(() -> {
            try {
                while (!socket.isClosed()) {
                    Thread.sleep(PING_EVERY_MS);
                    send("PING");
                }
            } catch (InterruptedException | IOException ignored) {
            }
        }, "heartbeat");
        heartbeat.setDaemon(true);
        heartbeat.start();
    }

    public synchronized void send(String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public String localEndpoint() {
        return socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort();
    }

    public void close() {
        closing = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
