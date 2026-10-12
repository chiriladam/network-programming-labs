import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Task 1b: all numbers are already in an int[]; T threads each count an equal
 * slice of the array, in three ways.
 *
 *   1 (unsync)  - all threads increment the SAME three counters with a plain
 *                 counter++ (read, add 1, write back). Not atomic: updates are lost.
 *   2 (lock)    - the same shared counters, but every increment is done while
 *                 holding one ReentrantLock. Correct, but threads queue for the lock.
 *   3 (local)   - each thread counts into its own local variables; the main
 *                 thread adds the per-thread results after join(). Correct and fast.
 */
public final class Task1b {
    private Task1b() { }

    /**
     * The shared counters of versions 1 and 2: plain fields, no volatile, no
     * atomics. counter++ compiles to three steps: load the value, add 1, store it.
     */
    static final class Shared {
        long negative;
        long positive;
        long zero;

        Counts toCounts() { return new Counts(negative, positive, zero); }
    }

    /** Loads the whole file into an array (not part of the measured time). */
    public static int[] load(Path file, long expectedCount) throws IOException {
        int[] data = new int[(int) Math.max(expectedCount, 16)];
        int n = 0;
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 20];
            boolean neg = false, has = false;
            int v = 0, r;
            while ((r = in.read(buf)) > 0) {
                for (int i = 0; i < r; i++) {
                    byte b = buf[i];
                    if (b >= '0' && b <= '9') { v = v * 10 + (b - '0'); has = true; }
                    else if (b == '-') neg = true;
                    else if (b == '\n') {
                        if (has) {
                            if (n == data.length) data = java.util.Arrays.copyOf(data, n * 2);
                            data[n++] = neg ? -v : v;
                        }
                        neg = false; has = false; v = 0;
                    }
                }
            }
            if (has) {
                if (n == data.length) data = java.util.Arrays.copyOf(data, n + 1);
                data[n++] = neg ? -v : v;
            }
        }
        return n == data.length ? data : java.util.Arrays.copyOf(data, n);
    }

    public static Counts run(int[] data, int threads, int version) throws InterruptedException {
        Shared shared = new Shared();
        ReentrantLock lock = new ReentrantLock();
        Counts[] local = new Counts[threads];
        Thread[] workers = new Thread[threads];

        for (int t = 0; t < threads; t++) {
            final int id = t;
            final int from = (int) ((long) data.length * t / threads);
            final int to = (int) ((long) data.length * (t + 1) / threads);
            Runnable job = switch (version) {
                case 1 -> () -> unsynchronised(data, from, to, shared);
                case 2 -> () -> withLock(data, from, to, shared, lock);
                case 3 -> () -> local[id] = perThread(data, from, to);
                default -> throw new IllegalArgumentException("version must be 1, 2 or 3");
            };
            workers[t] = new Thread(job, "1b-v" + version + "-" + t);
        }
        for (Thread w : workers) w.start();
        for (Thread w : workers) w.join();

        if (version == 3) {
            Counts total = new Counts();
            for (Counts c : local) total.add(c);
            return total;
        }
        return shared.toCounts();
    }

    // Version 1: shared counters, no synchronisation (a data race).
    static void unsynchronised(int[] a, int from, int to, Shared s) {
        for (int i = from; i < to; i++) {
            int v = a[i];
            if (v < 0) s.negative++;
            else if (v > 0) s.positive++;
            else s.zero++;
        }
    }

    // Version 2: shared counters, each increment under the lock.
    static void withLock(int[] a, int from, int to, Shared s, ReentrantLock lock) {
        for (int i = from; i < to; i++) {
            int v = a[i];
            lock.lock();
            try {
                if (v < 0) s.negative++;
                else if (v > 0) s.positive++;
                else s.zero++;
            } finally {
                lock.unlock();
            }
        }
    }

    // Version 3: counters local to the thread, merged after join().
    static Counts perThread(int[] a, int from, int to) {
        long neg = 0, pos = 0, zero = 0;
        for (int i = from; i < to; i++) {
            int v = a[i];
            if (v < 0) neg++;
            else if (v > 0) pos++;
            else zero++;
        }
        return new Counts(neg, pos, zero);
    }
}
