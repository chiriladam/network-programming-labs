import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Task 1a: the file is divided into as many byte ranges as there are threads.
 * Each thread opens the file itself, reads only its own range and parses it.
 *
 * Boundary rule (no line counted twice, none skipped):
 *   A line belongs to the part in which its FIRST byte lies.
 *   align(p) = the offset of the first line that starts at or after byte p:
 *              p itself if p == 0 or byte p-1 is '\n', otherwise the byte
 *              after the next '\n'.
 *   Thread i processes exactly the bytes [align(start_i), align(end_i)).
 *   Since end_i == start_(i+1), thread i stops exactly where thread i+1
 *   begins: the parts touch with no gap and no overlap.
 */
public final class Task1a {
    private Task1a() { }

    static final int BUFFER_SIZE = 1 << 20; // 1 MiB read buffer per thread

    public static Counts run(Path file, int threads) throws Exception {
        long size;
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            size = ch.size();
        }

        Counts[] results = new Counts[threads];
        Thread[] workers = new Thread[threads];
        Throwable[] errors = new Throwable[1];

        for (int i = 0; i < threads; i++) {
            final int id = i;
            final long start = size * i / threads;
            final long end = size * (i + 1) / threads;
            workers[i] = new Thread(() -> {
                try {
                    results[id] = countPart(file, size, start, end);
                } catch (Throwable t) {
                    errors[0] = t;
                }
            }, "1a-worker-" + i);
            workers[i].start();
        }
        for (Thread t : workers) t.join();
        if (errors[0] != null) throw new RuntimeException(errors[0]);

        Counts total = new Counts();
        for (Counts c : results) total.add(c);
        return total;
    }

    /** Reads and parses the lines that start inside [start, end). */
    static Counts countPart(Path file, long size, long start, long end) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            long from = align(ch, size, start);
            long to = align(ch, size, end);
            SignParser parser = new SignParser();
            ByteBuffer buf = ByteBuffer.allocate(BUFFER_SIZE);
            long pos = from;
            while (pos < to) {
                buf.clear();
                buf.limit((int) Math.min(BUFFER_SIZE, to - pos));
                int n = ch.read(buf, pos);
                if (n <= 0) break;
                parser.feed(buf.array(), 0, n);
                pos += n;
            }
            return parser.finish();
        }
    }

    /** The offset of the first line that starts at or after byte p. */
    static long align(FileChannel ch, long size, long p) throws IOException {
        if (p <= 0) return 0;
        if (p >= size) return size;
        ByteBuffer one = ByteBuffer.allocate(4096);
        long pos = p - 1; // look at the byte just before p
        while (pos < size) {
            one.clear();
            int n = ch.read(one, pos);
            if (n <= 0) return size;
            for (int i = 0; i < n; i++) {
                if (one.get(i) == '\n') return pos + i + 1;
            }
            pos += n;
        }
        return size;
    }
}
