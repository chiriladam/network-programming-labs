import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Laboratory Work 1: Concurrency in Practice (Network Programming).
 * One program; the task and its configuration are chosen on the command line.
 *
 *   java -cp out Lab1 gen   [--file numbers.txt] [--count 50000000] [--seed 42]
 *   java -cp out Lab1 split [--file numbers.txt] [--parts 8] [--dir parts]
 *   java -cp out Lab1 1a    --threads N
 *   java -cp out Lab1 1b    --version 1|2|3 [--threads N]   (default N = logical processors)
 *   java -cp out Lab1 1c    --workers N [--batch 10000] [--capacity 100]   (capacity 0 = unbounded)
 *   java -cp out Lab1 2     --threads N --dir E:\parts [--label usb]
 *   java -cp out Lab1 info
 *
 * Common options: --file numbers.txt  --csv results.csv  --run 1
 * Every task prints the counts, checks them against numbers.txt.counts
 * (written by gen), and appends one line to the CSV file.
 */
public final class Lab1 {

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.US);
        if (args.length == 0) { usage(); return; }
        String cmd = args[0];
        Map<String, String> opt = parse(args);
        Path file = Path.of(opt.getOrDefault("file", "numbers.txt"));
        int logical = Runtime.getRuntime().availableProcessors();

        switch (cmd) {
            case "gen" -> DataGen.generate(file,
                    Long.parseLong(opt.getOrDefault("count", "50000000")),
                    Long.parseLong(opt.getOrDefault("seed", "42")));
            case "split" -> DataGen.split(file,
                    Integer.parseInt(opt.getOrDefault("parts", "8")),
                    Path.of(opt.getOrDefault("dir", "parts")));
            case "info" -> info();
            case "1a" -> {
                int threads = intOpt(opt, "threads", 1);
                requireFile(file);
                Memory.resetHeapPeaks();
                long t0 = System.nanoTime();
                Counts c = Task1a.run(file, threads);
                long t1 = System.nanoTime();
                report(opt, file, "1a", "split-file", threads, t1 - t0, c);
            }
            case "1b" -> {
                int version = intOpt(opt, "version", 3);
                int threads = intOpt(opt, "threads", logical);
                requireFile(file);
                Counts expected = DataGen.readCounts(DataGen.countsFileFor(file));
                System.out.println("Loading " + file + " into an int[] (not measured)...");
                int[] data = Task1b.load(file, expected == null ? 50_000_000 : expected.total());
                Memory.resetHeapPeaks();
                long t0 = System.nanoTime();
                Counts c = Task1b.run(data, threads, version);
                long t1 = System.nanoTime();
                String name = switch (version) { case 1 -> "v1-unsync"; case 2 -> "v2-lock"; default -> "v3-local"; };
                report(opt, file, "1b", name, threads, t1 - t0, c);
            }
            case "1c" -> {
                int workers = intOpt(opt, "workers", 1);
                int batch = intOpt(opt, "batch", Task1c.BATCH_SIZE);
                int capacity = intOpt(opt, "capacity", Task1c.QUEUE_CAPACITY);
                requireFile(file);
                Memory.resetHeapPeaks();
                long t0 = System.nanoTime();
                Counts c = Task1c.run(file, workers, batch, capacity);
                long t1 = System.nanoTime();
                String cap = capacity > 0 ? String.valueOf(capacity) : "unbounded";
                report(opt, file, "1c", "batch=" + batch + ";cap=" + cap, workers, t1 - t0, c);
            }
            case "2" -> {
                int threads = intOpt(opt, "threads", 1);
                Path dir = Path.of(opt.getOrDefault("dir", "parts"));
                List<Path> files = Task2.partFiles(dir);
                System.out.println("Reading " + files.size() + " files from " + dir.toAbsolutePath());
                Memory.resetHeapPeaks();
                long t0 = System.nanoTime();
                Counts c = Task2.run(files, threads);
                long t1 = System.nanoTime();
                report(opt, file, "2", opt.getOrDefault("label", "usb"), threads, t1 - t0, c);
            }
            default -> usage();
        }
    }

    private static void report(Map<String, String> opt, Path numbersFile, String task, String variant,
                               int threads, long nanos, Counts c) throws IOException {
        double ms = nanos / 1e6;
        double heap = Memory.peakHeapMB();
        double process = Memory.peakProcessMB();
        Counts expected = DataGen.readCounts(DataGen.countsFileFor(numbersFile));
        String check;
        if (expected == null) check = "UNKNOWN";
        else check = c.sameAs(expected) ? "CORRECT" : "WRONG";

        System.out.println();
        System.out.printf("Task %s  [%s]  threads=%d%n", task, variant, threads);
        System.out.println("  " + c);
        if (expected != null) System.out.println("  expected " + expected);
        System.out.printf("  result: %s%n", check);
        System.out.printf("  time: %.1f ms%n", ms);
        System.out.printf("  peak memory (process): %.1f MB   peak Java heap: %.1f MB%n", process, heap);

        Path csv = Path.of(opt.getOrDefault("csv", "results.csv"));
        boolean newFile = !Files.exists(csv);
        String line = String.join(",",
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                task, variant, String.valueOf(threads), opt.getOrDefault("run", "1"),
                String.format("%.1f", ms), String.format("%.1f", process), String.format("%.1f", heap),
                String.valueOf(c.negative), String.valueOf(c.positive), String.valueOf(c.zero),
                String.valueOf(c.total()), check,
                String.valueOf(Runtime.getRuntime().availableProcessors()))
                + System.lineSeparator();
        if (newFile) {
            Files.writeString(csv, "timestamp,task,variant,threads,run,time_ms,peak_process_mb,peak_heap_mb,"
                    + "negative,positive,zero,total,check,logical_cpus" + System.lineSeparator());
        }
        Files.writeString(csv, line, StandardOpenOption.APPEND);
    }

    private static void info() {
        Runtime rt = Runtime.getRuntime();
        System.out.println("OS:                 " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " (" + System.getProperty("os.arch") + ")");
        System.out.println("Java:               " + System.getProperty("java.vendor") + " "
                + System.getProperty("java.runtime.version"));
        System.out.println("JVM:                " + System.getProperty("java.vm.name"));
        System.out.println("Logical processors: " + rt.availableProcessors());
        System.out.printf("Max heap:           %.0f MB%n", rt.maxMemory() / (1024.0 * 1024.0));
    }

    private static void requireFile(Path file) throws IOException {
        if (!Files.exists(file)) throw new IOException(file + " not found - run: java -cp out Lab1 gen");
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                String val = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                m.put(key, val);
            }
        }
        return m;
    }

    private static int intOpt(Map<String, String> opt, String key, int def) {
        return opt.containsKey(key) ? Integer.parseInt(opt.get(key)) : def;
    }

    private static void usage() {
        System.out.println("""
                Usage: java -cp out Lab1 <command> [options]
                  gen    [--file numbers.txt] [--count 50000000] [--seed 42]
                  split  [--file numbers.txt] [--parts 8] [--dir parts]
                  1a     --threads N
                  1b     --version 1|2|3 [--threads N]    (default: logical processors)
                  1c     --workers N [--batch 10000] [--capacity 100]   (capacity 0 = unbounded)
                  2      --threads N --dir <folder with part1.txt..part8.txt> [--label usb]
                  info
                Common: --file numbers.txt  --csv results.csv  --run <run number>""");
    }
}
