import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;

/**
 * The coffee shop server.
 *
 *   java -cp out Server <port> [--workers N] [--stock coffee=1,tea=2,croissant=3]
 *                              [--stock-file stock.txt] [--no-save] [--log server.log]
 *
 * Threads:
 *   main thread      accepts connections on the listening socket
 *   1 + 1 per conn.  a reader thread and a writer thread for every connection
 *   N workers        prepare the orders (N = logical processors)
 *   1 saver          writes the stock file
 */
public final class Server {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java -cp out Server <port> [--workers N] [--stock coffee=1,tea=2,croissant=3]");
            System.out.println("                             [--stock-file stock.txt] [--no-save] [--log server.log]");
            return;
        }
        int port;
        int workers = Runtime.getRuntime().availableProcessors();   // one worker per logical processor
        String stockArg = null;
        String stockFile = "stock.txt";
        boolean save = true;
        String logFile = "server.log";
        try {
            port = Integer.parseInt(args[0]);
            for (int i = 1; i < args.length; i++) {
                switch (args[i]) {
                    case "--workers" -> workers = Integer.parseInt(args[++i]);
                    case "--stock" -> stockArg = args[++i];
                    case "--stock-file" -> stockFile = args[++i];
                    case "--no-save" -> save = false;
                    case "--log" -> logFile = args[++i];
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            if (port < 1 || port > 65535 || workers < 1) throw new IllegalArgumentException("bad port or worker count");
        } catch (RuntimeException e) {
            System.out.println("Bad arguments: " + e.getMessage());
            return;
        }
        Log.toFile(logFile);

        // ---- the stock: from --stock, else from the saved file, else random 10..50
        StockStore store = save ? new StockStore(Path.of(stockFile)) : null;
        EnumMap<Product, Integer> initial;
        String source;
        if (stockArg != null) {
            initial = parseStock(stockArg);
            source = "given with --stock";
        } else if (store != null && (initial = store.load()) != null) {
            source = "loaded from " + stockFile;
        } else {
            initial = StockStore.randomStock();
            source = "random";
        }
        Shop shop = new Shop(initial, store);
        if (store != null) {
            store.attach(shop);
            Thread saver = new Thread(store, "stock-saver");
            saver.setDaemon(true);
            saver.start();
            store.requestSave();
            Runtime.getRuntime().addShutdownHook(new Thread(store::save));
        }

        // ---- the workers
        for (int i = 1; i <= workers; i++) {
            Thread w = new Thread(new Worker(i, shop.queue()), "worker-" + i);
            w.setDaemon(true);
            w.start();
        }

        // ---- socket(), bind(), listen(), then accept() forever
        try (ServerSocket listener = new ServerSocket()) {
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(port), 100);   // all network interfaces, backlog 100
            Log.log("server", "coffee shop listening on port " + port + ", " + workers + " workers");
            Log.log("server", "stock " + source + ": " + shop.menu().substring(5));
            Log.log("server", "addresses: " + localAddresses(port));
            while (true) {
                Socket socket;
                try {
                    socket = listener.accept();                 // a new socket for every client
                } catch (IOException e) {
                    Log.log("server", "ERROR accept failed: " + e.getMessage());
                    continue;
                }
                Thread t = new Thread(new ClientSession(socket, shop), "conn-" + socket.getPort());
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            Log.log("server", "ERROR cannot listen on port " + port + ": " + e.getMessage());
        }
    }

    /** "coffee=1,tea=2,croissant=3"; products not named get 0. */
    private static EnumMap<Product, Integer> parseStock(String s) {
        EnumMap<Product, Integer> m = new EnumMap<>(Product.class);
        for (Product p : Product.values()) m.put(p, 0);
        for (String part : s.split(",")) {
            String[] kv = part.split("=");
            Product p = Product.parse(kv[0].trim());
            if (p == null) throw new IllegalArgumentException("unknown product " + kv[0]);
            m.put(p, Integer.parseInt(kv[1].trim()));
        }
        return m;
    }

    /** The IPv4 addresses other computers can use to reach this server. */
    private static String localAddresses(int port) {
        StringBuilder sb = new StringBuilder("localhost:" + port);
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) sb.append(", ").append(a.getHostAddress()).append(':').append(port);
                }
            }
        } catch (IOException ignored) {
        }
        return sb.toString();
    }
}
