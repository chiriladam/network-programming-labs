import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Task 2: the 8 part files are the units of work.
 *
 * The files are dealt to the threads round-robin (file k goes to thread
 * k mod T), so with 8 files: 1 thread gets 8, 2 threads get 4 each,
 * 4 threads get 2 each. A thread reads a whole file with one
 * Files.readAllBytes() call into one byte[] and then parses that buffer.
 */
public final class Task2 {
    private Task2() { }

    public static List<Path> partFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = new ArrayList<>(s
                    .filter(p -> p.getFileName().toString().matches("part\\d+\\.txt"))
                    .toList());
            files.sort(Comparator.comparingInt(p ->
                    Integer.parseInt(p.getFileName().toString().replaceAll("\\D", ""))));
            if (files.isEmpty()) throw new IOException("No part*.txt files in " + dir);
            return files;
        }
    }

    public static Counts run(List<Path> files, int threads) throws Exception {
        Counts[] results = new Counts[threads];
        Thread[] workers = new Thread[threads];
        Throwable[] errors = new Throwable[1];

        for (int t = 0; t < threads; t++) {
            final int id = t;
            List<Path> mine = new ArrayList<>();
            for (int k = t; k < files.size(); k += threads) mine.add(files.get(k));

            workers[t] = new Thread(() -> {
                Counts c = new Counts();
                try {
                    for (Path f : mine) {
                        byte[] all = Files.readAllBytes(f);   // one read into one buffer
                        SignParser p = new SignParser();
                        p.feed(all, 0, all.length);
                        c.add(p.finish());
                    }
                } catch (Throwable e) {
                    errors[0] = e;
                }
                results[id] = c;
            }, "2-worker-" + t);
            workers[t].start();
        }
        for (Thread w : workers) w.join();
        if (errors[0] != null) throw new RuntimeException(errors[0]);

        Counts total = new Counts();
        for (Counts c : results) total.add(c);
        return total;
    }
}
