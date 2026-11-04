import java.util.EnumMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.TimeUnit;

/**
 * One order: its products, its preparation time and its state.
 *
 *   WAITING --worker takes it--> PREPARING --time is up--> READY
 *      \                            /
 *       +------ client gone -------+--> CANCELLED
 *
 * The state is changed only inside synchronized methods, so a worker that
 * finishes the order and a client thread that cancels it at the same moment
 * cannot both win: exactly one of READY or CANCELLED happens.
 */
public final class Order {

    public enum State { WAITING, PREPARING, READY, CANCELLED }

    public final int id;
    public final ClientSession owner;
    public final Map<Product, Integer> items;
    public final long seconds;
    private State state = State.WAITING;

    public Order(int id, ClientSession owner, Map<Product, Integer> items) {
        this.id = id;
        this.owner = owner;
        this.items = new EnumMap<>(items);
        long s = 0;
        for (Map.Entry<Product, Integer> e : items.entrySet()) s += (long) e.getKey().seconds * e.getValue();
        this.seconds = s;
    }

    public synchronized State state() {
        return state;
    }

    /** Called by a worker. False if the order was cancelled while it waited in the queue. */
    public synchronized boolean startPreparing() {
        if (state != State.WAITING) return false;
        state = State.PREPARING;
        return true;
    }

    /**
     * Called by the worker: simulates the preparation by waiting `seconds`.
     * The wait is wait(timeout) on this object, so cancel() can wake the worker
     * at once with notifyAll().
     * @return true if the order is ready, false if it was cancelled meanwhile
     */
    public synchronized boolean prepare() throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (state == State.PREPARING) {
            long left = end - System.nanoTime();
            if (left <= 0) {
                state = State.READY;
                return true;
            }
            TimeUnit.NANOSECONDS.timedWait(this, left);   // releases the lock while waiting
        }
        return false;                                       // cancel() changed the state
    }

    /**
     * Called by the client's thread when the client disappears.
     * @return true if the order was waiting or being prepared (its products must go back)
     */
    public synchronized boolean cancel() {
        if (state == State.WAITING || state == State.PREPARING) {
            state = State.CANCELLED;
            notifyAll();                                    // wakes the worker inside prepare()
            return true;
        }
        return false;
    }

    /** "2 croissant, 1 tea" */
    public static String describe(Map<Product, Integer> items) {
        StringJoiner j = new StringJoiner(", ");
        for (Map.Entry<Product, Integer> e : items.entrySet()) j.add(e.getValue() + " " + e.getKey().label);
        return j.toString();
    }
}
