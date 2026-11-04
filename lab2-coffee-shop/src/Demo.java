import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demonstrations for the defense, with many real clients at once (each one a
 * separate TCP connection, exactly like Client):
 *
 *   java -cp out Demo <host> <port> many <N> [product]
 *       N clients order 1 product each at the same moment. With more clients
 *       than workers, the later orders wait in the queue: their READY comes
 *       one preparation time later.
 *
 *   java -cp out Demo <host> <port> rush <N> [product]
 *       N clients order the same product at the same moment (e.g. the last
 *       croissant). Shows that no more are sold than the stock had.
 */
public final class Demo {

    private static final long T0 = System.nanoTime();

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("Usage: java -cp out Demo <host> <port> many|rush <clients> [product]");
            return;
        }
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String mode = args[2];
        int n = Integer.parseInt(args[3]);
        String product = args.length > 4 ? args[4] : (mode.equals("rush") ? "croissant" : "coffee");
        switch (mode) {
            case "many" -> many(host, port, n, product);
            case "rush" -> rush(host, port, n, product);
            default -> System.out.println("Unknown mode " + mode + ", use many or rush");
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------ many clients

    private static void many(String host, int port, int n, String product) throws Exception {
        System.out.println(n + " clients each order 1 " + product + " at the same moment.");
        List<DemoClient> clients = connectAll(host, port, n);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (DemoClient c : clients) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    c.conn.send("ORDER " + product + " 1");
                    String answer = c.next("ACCEPTED", "REFUSED", "ERROR");
                    say(c, answer);
                    if (answer.startsWith("ACCEPTED")) say(c, c.next("READY"));
                } catch (Exception e) {
                    say(c, "failed: " + e.getMessage());
                }
            });
            t.start();
            threads.add(t);
        }
        long start = System.nanoTime();
        go.countDown();
        for (Thread t : threads) t.join();
        System.out.printf("All done in %.1f s.%n", (System.nanoTime() - start) / 1e9);
        clients.forEach(c -> c.conn.close());
    }

    // ------------------------------------------------------------------ rush for the last items

    private static void rush(String host, int port, int n, String product) throws Exception {
        DemoClient observer = connectAll(host, port, 1).get(0);
        int before = stockOf(observer, product);
        System.out.println("Stock of " + product + " before: " + before + ". Now " + n
                + " clients order 1 " + product + " each, at the same moment.");

        List<DemoClient> clients = connectAll(host, port, n);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (DemoClient c : clients) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    c.conn.send("ORDER " + product + " 1");
                    String answer = c.next("ACCEPTED", "REFUSED", "ERROR");
                    say(c, answer);
                    if (answer.startsWith("ACCEPTED")) {
                        accepted.incrementAndGet();
                        c.next("READY");                 // stay connected until it is ready
                    } else {
                        refused.incrementAndGet();
                    }
                } catch (Exception e) {
                    say(c, "failed: " + e.getMessage());
                }
            });
            t.start();
            threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) t.join();
        int after = stockOf(observer, product);
        System.out.println();
        System.out.println("Accepted: " + accepted + ", refused: " + refused);
        System.out.println("Stock of " + product + ": " + before + " before, " + after + " after");
        boolean ok = accepted.get() == Math.min(n, before) && after == before - accepted.get();
        System.out.println(ok ? "OK: exactly as many were sold as the stock had, none twice."
                              : "NOT OK: the numbers do not add up!");
        clients.forEach(c -> c.conn.close());
        observer.conn.close();
    }

    // ------------------------------------------------------------------ helpers

    /** One demo connection whose messages are collected in a queue. */
    private static final class DemoClient {
        final int number;
        final ShopConnection conn = new ShopConnection();
        final BlockingQueue<String> inbox = new LinkedBlockingQueue<>();

        DemoClient(int number) {
            this.number = number;
        }

        /** The next message that starts with one of the given words (waits up to 10 minutes). */
        String next(String... starts) throws InterruptedException, IOException {
            while (true) {
                String m = inbox.poll(10, TimeUnit.MINUTES);
                if (m == null || m.startsWith("<closed>")) throw new IOException(m == null ? "timeout" : m);
                for (String s : starts) if (m.startsWith(s)) return m;
            }
        }
    }

    private static List<DemoClient> connectAll(String host, int port, int n) throws Exception {
        List<DemoClient> list = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            DemoClient c = new DemoClient(i);
            c.conn.connect(host, port);
            c.conn.start(c.inbox::add, reason -> c.inbox.add("<closed> " + reason));
            c.conn.send("HELLO CLIENT");
            c.next("OK");
            list.add(c);
        }
        return list;
    }

    private static int stockOf(DemoClient c, String product) throws Exception {
        c.conn.send("MENU");
        String menu = c.next("MENU");
        Matcher m = Pattern.compile("(\\w+) (\\d+) in stock").matcher(menu);
        while (m.find()) {
            if (Product.parse(m.group(1)) == Product.parse(product)) return Integer.parseInt(m.group(2));
        }
        throw new IOException("product not in the menu: " + product);
    }

    private static synchronized void say(DemoClient c, String text) {
        System.out.printf("%6.2fs  client %2d (%s)  %s%n", (System.nanoTime() - T0) / 1e9, c.number,
                c.conn.localEndpoint(), text);
    }
}
