import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * One connection (a client or the provider). It has two threads:
 *
 *   reader thread (run): receives messages, executes the commands, and puts
 *                        the answers in the outbox;
 *   writer thread:       takes lines from the outbox and sends them.
 *
 * Every message to this connection, from its own reader thread or from a
 * worker ("READY") or from another client's thread (an "ALERT" to the
 * provider), goes through send(), which only adds the line to the outbox.
 * So only ONE thread ever writes to the socket: two messages can never be
 * mixed byte by byte, and a worker never blocks on a slow or dead client.
 * Reading and writing the same socket from two threads at the same time is
 * fine: TCP is full-duplex.
 */
public final class ClientSession implements Runnable {

    public static final int MAX_LINE = 1024;          // longer messages are refused
    public static final int IDLE_TIMEOUT_MS = 30_000; // silent this long -> considered gone
    private static final int OUTBOX_LIMIT = 1000;     // a client that never reads is dropped
    private static final String CLOSE = new String("<close>"); // marker for the writer thread

    private final Socket socket;
    private final Shop shop;
    private final String endpoint;
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>(OUTBOX_LIMIT);
    private final Set<Order> myOrders = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private boolean isProvider;                       // used only by the reader thread
    private boolean roleChosen;

    public ClientSession(Socket socket, Shop shop) {
        this.socket = socket;
        this.shop = shop;
        this.endpoint = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
    }

    public String endpoint() {
        return endpoint;
    }

    /** Queues one line for this connection. Thread-safe and never blocks. */
    public void send(String line) {
        if (closed) return;
        if (!outbox.offer(line)) {
            Log.log(endpoint, "ERROR the client does not read its messages, closing the connection");
            closeNow();
        }
    }

    public void orderFinished(Order order) {
        myOrders.remove(order);
    }

    // ------------------------------------------------------------------ reader thread

    @Override
    public void run() {
        Thread writer = new Thread(this::writeLoop, "writer-" + endpoint);
        writer.setDaemon(true);
        writer.start();
        Log.log(endpoint, "CONNECTED");
        send("WELCOME to the coffee shop. Type HELP for the commands.");

        String reason = "closed the connection";
        try {
            socket.setSoTimeout(IDLE_TIMEOUT_MS);       // receive() gives up after this much silence
            LineReader reader = new LineReader(socket.getInputStream(), MAX_LINE);
            while (true) {
                String line;
                try {
                    line = reader.readLine();
                } catch (LineReader.LineTooLongException e) {
                    error("message too long (more than " + MAX_LINE + " bytes)");
                    continue;
                }
                if (line == null) break;                // receive() returned -1: the client closed
                if (!handle(line)) {                    // QUIT
                    reason = "said QUIT";
                    break;
                }
            }
        } catch (SocketTimeoutException e) {
            reason = "was silent for " + IDLE_TIMEOUT_MS / 1000 + "s (network gone?)";
        } catch (IOException e) {
            reason = "connection lost: " + e.getMessage();   // e.g. "Connection reset"
        } catch (RuntimeException e) {
            reason = "internal error: " + e;                 // never let one client crash the server
        } finally {
            disconnected(reason);
        }
    }

    /** Cleans up after the client is gone: cancels its orders, frees the provider role. */
    private void disconnected(String reason) {
        closed = true;
        Log.log(endpoint, "DISCONNECTED: " + reason);
        for (Order o : myOrders) {
            if (shop.cancel(o)) {
                Log.log(endpoint, "order #" + o.id + " CANCELLED, products back in stock: " + Order.describe(o.items));
            }
        }
        myOrders.clear();
        if (isProvider) {
            shop.unregisterProvider(this);
            Log.log(endpoint, "the provider left; the shop goes on with its stock");
        }
        // the writer sends what is still queued (e.g. "BYE"), then closes the socket
        if (!outbox.offer(CLOSE)) closeNow();
    }

    /** Executes one message. Returns false when the connection must end (QUIT). */
    private boolean handle(String line) {
        String text = line.trim();
        if (text.isEmpty()) return true;
        if (text.equalsIgnoreCase("PING")) {            // heartbeat of our client program
            send("PONG");
            return true;
        }
        String[] w = text.replace(',', ' ').trim().split("\\s+");
        String command = w[0].toUpperCase(Locale.ROOT);
        switch (command) {
            case "HELLO" -> hello(w);
            case "MENU" -> {
                send(shop.menu());
                Log.log(endpoint, "MENU sent");
            }
            case "ORDER" -> order(w);
            case "DELIVER" -> deliver(w);
            case "HELP" -> help();
            case "QUIT" -> {
                send("BYE");
                return false;
            }
            default -> error("unknown command '" + Log.preview(w[0]) + "'. Type HELP for the commands.");
        }
        return true;
    }

    private void hello(String[] w) {
        if (w.length != 2) {
            error("use: HELLO CLIENT or HELLO PROVIDER");
            return;
        }
        if (roleChosen) {
            error("the role was already chosen");
            return;
        }
        String role = w[1].toUpperCase(Locale.ROOT);
        if (role.equals("CLIENT")) {
            roleChosen = true;
            send("OK you are a client");
            Log.log(endpoint, "role: client");
        } else if (role.equals("PROVIDER")) {
            if (!myOrders.isEmpty()) {
                error("a connection that has orders cannot become the provider");
            } else if (shop.registerProvider(this)) {
                roleChosen = true;
                isProvider = true;
                send("OK you are the provider");
                Log.log(endpoint, "role: PROVIDER");
            } else {
                error("a provider is already connected");
            }
        } else {
            error("unknown role '" + Log.preview(w[1]) + "', use CLIENT or PROVIDER");
        }
    }

    private void order(String[] w) {
        if (isProvider) {
            error("the provider cannot order");
            return;
        }
        Map<Product, Integer> items;
        try {
            items = parseItems(w, "ORDER coffee 1 tea 2");
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }
        Shop.Result r = shop.placeOrder(this, items);
        if (r.order() == null) {
            send("REFUSED " + r.refusal());
            Log.log(endpoint, "order REFUSED (" + Order.describe(items) + "): " + r.refusal());
            return;
        }
        Order o = r.order();
        myOrders.add(o);
        send("ACCEPTED order #" + o.id + ": " + Order.describe(items) + ", preparation time " + o.seconds + "s");
        Log.log(endpoint, "order #" + o.id + " ACCEPTED: " + Order.describe(items)
                + " (" + o.seconds + "s), waiting orders: " + shop.queue().size());
    }

    private void deliver(String[] w) {
        if (!isProvider) {
            error("only the provider can deliver");
            return;
        }
        Map<Product, Integer> items;
        try {
            items = parseItems(w, "DELIVER coffee 20 croissant 10");
        } catch (IllegalArgumentException e) {
            error(e.getMessage());
            return;
        }
        String problem = shop.deliver(items);
        if (problem != null) {
            error(problem);
            return;
        }
        send("DELIVERED " + Order.describe(items) + ". " + shop.menu());
        Log.log(endpoint, "DELIVERY: " + Order.describe(items));
    }

    /** "ORDER coffee 2 tea 1" -> {COFFEE=2, TEA=1}; throws with a readable message. */
    private static Map<Product, Integer> parseItems(String[] w, String example) {
        if (w.length < 2) throw new IllegalArgumentException("missing products, use: " + example);
        if ((w.length - 1) % 2 != 0) {
            throw new IllegalArgumentException("every product needs a quantity, use: " + example);
        }
        Map<Product, Integer> items = new EnumMap<>(Product.class);
        for (int i = 1; i < w.length; i += 2) {
            Product p = Product.parse(w[i]);
            if (p == null) {
                throw new IllegalArgumentException("unknown product '" + Log.preview(w[i])
                        + "', we have: coffee, tea, croissant");
            }
            int n;
            try {
                n = Integer.parseInt(w[i + 1]);
            } catch (NumberFormatException e) {
                n = -1;
            }
            if (n <= 0) {
                throw new IllegalArgumentException("the quantity of " + p.label
                        + " must be a positive number, not '" + Log.preview(w[i + 1]) + "'");
            }
            long sum = (long) items.getOrDefault(p, 0) + n;
            if (sum > Shop.MAX_STOCK) throw new IllegalArgumentException("the quantity of " + p.label + " is too large");
            items.put(p, (int) sum);
        }
        return items;
    }

    private void help() {
        send("HELP MENU                          products and stock");
        send("HELP ORDER coffee 1 croissant 2    order products");
        send("HELP DELIVER coffee 20 tea 10      (provider only) bring products");
        send("HELP QUIT                          leave");
    }

    private void error(String message) {
        send("ERROR " + message);
        Log.log(endpoint, "ERROR " + message);
    }

    // ------------------------------------------------------------------ writer thread

    private void writeLoop() {
        try {
            OutputStream out = socket.getOutputStream();
            while (true) {
                String line = outbox.take();
                if (line == CLOSE) break;
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException e) {
            // the connection is broken; the reader thread will notice and clean up
        } finally {
            closeNow();
        }
    }

    private void closeNow() {
        closed = true;
        try {
            socket.close();                             // also wakes the reader thread
        } catch (IOException ignored) {
        }
    }
}
