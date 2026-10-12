import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Peak memory of this process.
 *
 * peakProcessMB(): the most physical memory the whole process ever used, as the
 * operating system sees it (heap, thread stacks, JVM code, buffers):
 *   Windows: PeakWorkingSet64 of this process (asked through PowerShell)
 *   Linux:   VmHWM from /proc/self/status
 *
 * peakHeapMB(): the highest Java heap usage the JVM recorded (sum of the peak
 * of each heap pool, so it is an upper bound).
 */
public final class Memory {
    private Memory() { }

    public static void resetHeapPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) pool.resetPeakUsage();
        }
    }

    public static double peakHeapMB() {
        long sum = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP && pool.getPeakUsage() != null) {
                sum += pool.getPeakUsage().getUsed();
            }
        }
        return sum / (1024.0 * 1024.0);
    }

    public static double peakProcessMB() {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("win")) return windowsPeak();
            if (os.contains("linux")) return linuxPeak();
        } catch (Exception e) {
            System.err.println("Could not read peak process memory: " + e.getMessage());
        }
        return -1;
    }

    private static double linuxPeak() throws Exception {
        for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
            if (line.startsWith("VmHWM:")) {
                long kb = Long.parseLong(line.replaceAll("[^0-9]", ""));
                return kb / 1024.0;
            }
        }
        return -1;
    }

    private static double windowsPeak() throws Exception {
        long pid = ProcessHandle.current().pid();
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                "(Get-Process -Id " + pid + ").PeakWorkingSet64")
                .redirectErrorStream(true).start();
        String out;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            out = r.readLine();
        }
        p.waitFor();
        return Long.parseLong(out.trim()) / (1024.0 * 1024.0);
    }
}
