import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

/** Creates numbers.txt and splits it into equal parts for Task 2. */
public final class DataGen {
    private DataGen() { }

    public static final int MIN = -1_000_000;
    public static final int MAX = 1_000_000;

    /**
     * Writes `count` random integers in [MIN, MAX], one per line.
     * Also writes <file>.counts with the true number of negative, positive
     * and zero values, which every task compares its result against.
     */
    public static void generate(Path file, long count, long seed) throws IOException {
        SplittableRandom rnd = new SplittableRandom(seed);
        Counts truth = new Counts();
        byte[] digits = new byte[12];
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 20)) {
            for (long i = 0; i < count; i++) {
                int v = rnd.nextInt(MIN, MAX + 1);
                truth.add(v);
                writeInt(out, v, digits);
                out.write('\n');
            }
        }
        writeCounts(countsFileFor(file), truth);
        System.out.println("Wrote " + file + " (" + Files.size(file) + " bytes)");
        System.out.println("Expected: " + truth);
    }

    /** Splits the file, in order, into `parts` files with the same number of lines. */
    public static void split(Path file, int parts, Path outDir) throws IOException {
        Counts expected = readCounts(countsFileFor(file));
        if (expected == null) throw new IOException("Missing " + countsFileFor(file) + " - run gen first");
        long linesPerPart = expected.total() / parts;
        Files.createDirectories(outDir);

        try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 20)) {
            byte[] buf = new byte[1 << 20];
            int part = 1;
            long linesInPart = 0;
            OutputStream out = openPart(outDir, part);
            int n;
            while ((n = in.read(buf)) > 0) {
                int start = 0;
                for (int i = 0; i < n; i++) {
                    if (buf[i] == '\n') {
                        linesInPart++;
                        if (linesInPart == linesPerPart && part < parts) {
                            out.write(buf, start, i + 1 - start);
                            out.close();
                            System.out.println("  part" + part + ".txt: " + linesInPart + " lines");
                            part++;
                            linesInPart = 0;
                            out = openPart(outDir, part);
                            start = i + 1;
                        }
                    }
                }
                out.write(buf, start, n - start);
            }
            out.close();
            System.out.println("  part" + part + ".txt: " + linesInPart + " lines");
        }
    }

    private static OutputStream openPart(Path dir, int part) throws IOException {
        return new BufferedOutputStream(Files.newOutputStream(dir.resolve("part" + part + ".txt")), 1 << 20);
    }

    public static Path countsFileFor(Path numbersFile) {
        return numbersFile.resolveSibling(numbersFile.getFileName() + ".counts");
    }

    public static void writeCounts(Path path, Counts c) throws IOException {
        Files.writeString(path, c.negative + " " + c.positive + " " + c.zero + System.lineSeparator());
    }

    /** Returns null if the file does not exist. */
    public static Counts readCounts(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        String[] p = Files.readString(path).trim().split("\\s+");
        return new Counts(Long.parseLong(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2]));
    }

    private static void writeInt(OutputStream out, int v, byte[] tmp) throws IOException {
        if (v == 0) { out.write('0'); return; }
        if (v < 0) { out.write('-'); v = -v; }
        int pos = tmp.length;
        while (v > 0) { tmp[--pos] = (byte) ('0' + v % 10); v /= 10; }
        out.write(tmp, pos, tmp.length - pos);
    }
}
