import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** One log line per event, with the time and the endpoint of the connection. */
public final class Log {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static PrintWriter file;

    private Log() { }

    /** Also write every line to this file (appended). */
    public static synchronized void toFile(String path) {
        try {
            file = new PrintWriter(new FileWriter(path, true), true);
        } catch (IOException e) {
            System.err.println("Cannot open log file " + path + ": " + e.getMessage());
        }
    }

    public static synchronized void log(String endpoint, String message) {
        String line = String.format("%s  %-22s %s", LocalTime.now().format(TIME), "[" + endpoint + "]", message);
        System.out.println(line);
        if (file != null) file.println(line);
    }

    /** Makes received text safe to print: control bytes are shown as \xNN, long text is cut. */
    public static String preview(String text) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && sb.length() < 60; i++) {
            char c = text.charAt(i);
            if (c >= 32 && c < 127) sb.append(c);
            else sb.append(String.format("\\x%02X", (int) c & 0xFF));
        }
        if (text.length() > 60) sb.append("...");
        return sb.toString();
    }
}
