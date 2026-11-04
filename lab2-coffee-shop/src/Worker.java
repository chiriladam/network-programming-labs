import java.util.concurrent.BlockingQueue;

/**
 * A worker: takes the oldest waiting order from the queue, prepares it
 * (waits its preparation time) and tells the client that it is ready.
 * There is one worker per logical processor; each prepares one whole order at a time.
 */
public final class Worker implements Runnable {

    private final int number;
    private final BlockingQueue<Order> queue;

    public Worker(int number, BlockingQueue<Order> queue) {
        this.number = number;
        this.queue = queue;
    }

    @Override
    public void run() {
        while (true) {
            Order order;
            try {
                order = queue.take();                       // waits while the queue is empty
            } catch (InterruptedException e) {
                return;
            }
            String who = order.owner.endpoint();
            if (!order.startPreparing()) {                  // cancelled while it was waiting
                Log.log(who, "order #" + order.id + " skipped by worker " + number + ": it was cancelled");
                continue;
            }
            Log.log(who, "order #" + order.id + " PREPARING by worker " + number + " (" + order.seconds + "s)");
            try {
                if (order.prepare()) {
                    order.owner.orderFinished(order);
                    order.owner.send("READY order #" + order.id + " (" + Order.describe(order.items) + ")");
                    Log.log(who, "order #" + order.id + " READY");
                } else {
                    Log.log(who, "order #" + order.id + " CANCELLED, worker " + number + " stopped preparing it");
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
