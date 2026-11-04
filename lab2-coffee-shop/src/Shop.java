import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The state shared by all threads of the server, and how each part is protected:
 *
 *   stock        EnumMap, read and changed only while holding stockLock.
 *                Checking that there is enough AND taking the products happen
 *                inside one synchronized block, so two clients can never both
 *                get the last croissant.
 *   queue        LinkedBlockingQueue: thread-safe and FIFO. Client threads put
 *                orders in (producers), workers take them out (consumers).
 *   nextOrderId  read and incremented while holding stockLock.
 *   provider     the provider's connection, read and changed only while
 *                holding providerLock.
 *   each Order   its own lock (see Order), so finish and cancel cannot both win.
 */
public final class Shop {

    public static final int LOW_STOCK = 5;          // alert the provider below this
    public static final int MAX_STOCK = 1_000_000;  // a delivery may not go above this

    private final Object stockLock = new Object();
    private final EnumMap<Product, Integer> stock;
    private int nextOrderId = 1;

    private final BlockingQueue<Order> queue = new LinkedBlockingQueue<>();

    private final Object providerLock = new Object();
    private ClientSession provider;

    private final StockStore store;                 // may be null: no saving

    public Shop(Map<Product, Integer> initialStock, StockStore store) {
        this.stock = new EnumMap<>(initialStock);
        this.store = store;
    }

    public BlockingQueue<Order> queue() {
        return queue;
    }

    /** A copy of the stock, taken under the lock (for the menu and for saving). */
    public EnumMap<Product, Integer> snapshot() {
        synchronized (stockLock) {
            return new EnumMap<>(stock);
        }
    }

    public String menu() {
        EnumMap<Product, Integer> s = snapshot();
        StringJoiner j = new StringJoiner(", ", "MENU ", "");
        for (Product p : Product.values()) j.add(p.label + " " + s.get(p) + " in stock (" + p.seconds + "s)");
        return j.toString();
    }

    // ------------------------------------------------------------------ orders

    /** The answer to an order: either the accepted order, or why it was refused. */
    public record Result(Order order, String refusal) { }

    /**
     * Checks the stock and takes the products in ONE critical section, then
     * puts the order in the queue for the workers.
     */
    public Result placeOrder(ClientSession client, Map<Product, Integer> items) {
        List<Product> fellLow = new ArrayList<>();
        Order order;
        synchronized (stockLock) {
            // 1. check every product first, so a refused order takes nothing
            for (Map.Entry<Product, Integer> e : items.entrySet()) {
                int left = stock.get(e.getKey());
                if (e.getValue() > left) {
                    return new Result(null, "not enough " + e.getKey().label
                            + ": you asked for " + e.getValue() + ", only " + left + " left");
                }
            }
            // 2. take them all
            for (Map.Entry<Product, Integer> e : items.entrySet()) {
                int before = stock.get(e.getKey());
                int after = before - e.getValue();
                stock.put(e.getKey(), after);
                if (before >= LOW_STOCK && after < LOW_STOCK) fellLow.add(e.getKey());
            }
            order = new Order(nextOrderId++, client, items);
            client.orderAccepted(order, queue.size()); // answer ACCEPTED first (only queues a line)
            queue.add(order);                       // then hand it to the workers; inside the lock,
                                                    // so the queue order = the order of the ids
        }
        stockChanged();
        // the alert is sent after the lock is released, so a slow provider never blocks the stock
        for (Product p : fellLow) alertProvider(p);
        return new Result(order, null);
    }

    /** The client disappeared: cancel the order; if it was waiting or being prepared, return its products. */
    public boolean cancel(Order order) {
        if (!order.cancel()) return false;          // already ready (or already cancelled)
        synchronized (stockLock) {
            for (Map.Entry<Product, Integer> e : order.items.entrySet()) {
                stock.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        stockChanged();
        return true;
    }

    // ------------------------------------------------------------------ provider

    /** Adds a delivery to the stock. Returns null if done, or an error message. */
    public String deliver(Map<Product, Integer> items) {
        synchronized (stockLock) {
            for (Map.Entry<Product, Integer> e : items.entrySet()) {
                if ((long) stock.get(e.getKey()) + e.getValue() > MAX_STOCK) {
                    return "the stock of " + e.getKey().label + " cannot go above " + MAX_STOCK;
                }
            }
            for (Map.Entry<Product, Integer> e : items.entrySet()) {
                stock.merge(e.getKey(), e.getValue(), Integer::sum);
            }
        }
        stockChanged();
        return null;
    }

    /**
     * Makes this connection the provider. Then sends it an alert for every
     * product that is already below LOW_STOCK: these are the alerts it missed
     * while it was not connected.
     */
    public boolean registerProvider(ClientSession session) {
        synchronized (providerLock) {
            if (provider != null) return false;     // only one provider at a time
            provider = session;
        }
        EnumMap<Product, Integer> s = snapshot();
        for (Product p : Product.values()) {
            if (s.get(p) < LOW_STOCK) {
                session.send("ALERT " + p.label + " is low: " + s.get(p) + " left (while you were away)");
                Log.log(session.endpoint(), "ALERT sent on connect: " + p.label + " " + s.get(p) + " left");
            }
        }
        return true;
    }

    public void unregisterProvider(ClientSession session) {
        synchronized (providerLock) {
            if (provider == session) provider = null;
        }
    }

    /** Called by the thread of the client whose order made the product fall below LOW_STOCK. */
    private void alertProvider(Product p) {
        ClientSession pv;
        synchronized (providerLock) {
            pv = provider;
        }
        int left = snapshot().get(p);
        if (pv != null) {
            pv.send("ALERT " + p.label + " is low: " + left + " left");
            Log.log(pv.endpoint(), "ALERT sent: " + p.label + " " + left + " left");
        } else {
            Log.log("shop", "ALERT pending: " + p.label + " " + left + " left, provider not connected");
        }
    }

    private void stockChanged() {
        if (store != null) store.requestSave();
    }
}
