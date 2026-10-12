import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Task 1c: producer-consumer.
 *
 * One reader thread reads the file line by line and puts the lines, in batches
 * of BATCH_SIZE, into an ArrayBlockingQueue with room for QUEUE_CAPACITY
 * batches. put() blocks while the queue is full; take() blocks while it is empty.
 *
 * Closing the queue: at the end of the file the reader puts one special
 * "end" batch per worker (a poison pill). Because the queue is FIFO, the pills
 * come after every real batch, so a worker that takes a pill knows the queue is
 * closed and empty, and stops.
 */
public final class Task1c {
    private Task1c() { }

    public static final int BATCH_SIZE = 10_000;
    public static final int QUEUE_CAPACITY = 100;

    /** The "queue closed" marker. Compared by reference, never counted. */
    private static final List<String> END = new ArrayList<>(0);

    public static Counts run(Path file, int workers) throws Exception {
        return run(file, workers, BATCH_SIZE, QUEUE_CAPACITY);
    }

    public static Counts run(Path file, int workers, int batchSize, int capacity) throws Exception {
        // capacity 0 = no size limit (only for the experiment in question 6)
        BlockingQueue<List<String>> queue = capacity > 0
                ? new ArrayBlockingQueue<>(capacity)
                : new LinkedBlockingQueue<>();
        Counts[] results = new Counts[workers];
        Throwable[] errors = new Throwable[1];

        Thread reader = new Thread(() -> {
            try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.US_ASCII)) {
                List<String> batch = new ArrayList<>(batchSize);
                String line;
                while ((line = in.readLine()) != null) {
                    batch.add(line);
                    if (batch.size() == batchSize) {
                        queue.put(batch);                // waits if the queue is full
                        batch = new ArrayList<>(batchSize);
                    }
                }
                if (!batch.isEmpty()) queue.put(batch);
            } catch (Throwable t) {
                errors[0] = t;
            } finally {
                // close the queue: one END marker per worker
                for (int i = 0; i < workers; i++) {
                    try { queue.put(END); } catch (InterruptedException ignored) { }
                }
            }
        }, "1c-reader");

        Thread[] pool = new Thread[workers];
        for (int w = 0; w < workers; w++) {
            final int id = w;
            pool[w] = new Thread(() -> {
                Counts mine = new Counts();
                try {
                    while (true) {
                        List<String> batch = queue.take();  // waits if the queue is empty
                        if (batch == END) break;
                        for (String s : batch) {
                            s = s.trim();
                            if (!s.isEmpty()) mine.add(Integer.parseInt(s));
                        }
                    }
                } catch (Throwable t) {
                    errors[0] = t;
                }
                results[id] = mine;
            }, "1c-worker-" + w);
        }

        reader.start();
        for (Thread t : pool) t.start();
        reader.join();
        for (Thread t : pool) t.join();
        if (errors[0] != null) throw new RuntimeException(errors[0]);

        Counts total = new Counts();
        for (Counts c : results) total.add(c);
        return total;
    }
}
