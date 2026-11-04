import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bonus: keeps the stock in a text file, so it survives a restart of the server.
 *
 * Client threads only call requestSave(), which sets a flag and returns at
 * once; one saver thread writes the file. It always writes a fresh snapshot of
 * the stock, so the file never goes back to an older state, and the disk is
 * never touched while the stock lock is held.
 * The file is written to a temporary file first and then renamed, so a crash
 * in the middle of a write never leaves a half-written stock file.
 */
public final class StockStore implements Runnable {

    private final Path file;
    private Shop shop;
    private boolean dirty;              // guarded by this

    public StockStore(Path file) {
        this.file = file;
    }

    public void attach(Shop shop) {
        this.shop = shop;
    }

    public synchronized void requestSave() {
        dirty = true;
        notifyAll();
    }

    @Override
    public void run() {
        while (true) {
            synchronized (this) {
                while (!dirty) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                dirty = false;
            }
            save();
        }
    }

    /** Writes the current stock now (also used on shutdown). */
    public void save() {
        EnumMap<Product, Integer> s = shop.snapshot();
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Product, Integer> e : s.entrySet()) {
            sb.append(e.getKey().label).append('=').append(e.getValue()).append(System.lineSeparator());
        }
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Log.log("shop", "ERROR cannot save the stock to " + file + ": " + e.getMessage());
        }
    }

    /** The saved stock, or null if there is no valid file. */
    public EnumMap<Product, Integer> load() {
        if (!Files.exists(file)) return null;
        try {
            EnumMap<Product, Integer> s = new EnumMap<>(Product.class);
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] kv = line.split("=");
                Product p = Product.parse(kv[0].trim());
                int n = Integer.parseInt(kv[1].trim());
                if (p == null || n < 0) throw new IllegalArgumentException(line);
                s.put(p, n);
            }
            if (s.size() != Product.values().length) throw new IllegalArgumentException("missing products");
            return s;
        } catch (Exception e) {
            Log.log("shop", "ERROR stock file " + file + " is not valid (" + e.getMessage() + "), using a random stock");
            return null;
        }
    }

    /** 10 to 50 of each product. */
    public static EnumMap<Product, Integer> randomStock() {
        EnumMap<Product, Integer> s = new EnumMap<>(Product.class);
        for (Product p : Product.values()) s.put(p, ThreadLocalRandom.current().nextInt(10, 51));
        return s;
    }
}
