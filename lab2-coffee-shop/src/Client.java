import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * The interactive console program, as a client or as the provider:
 *
 *   java -cp out Client <host> <port>             a client
 *   java -cp out Client <host> <port> provider    the provider
 *
 * The main thread reads the keyboard and sends each line to the server.
 * A second thread (in ShopConnection) receives the server's messages and
 * prints them at once, even while the user is typing: "READY" and "ALERT"
 * arrive without any request, so the program must not wait for the user
 * before it reads the socket.
 */
public final class Client {

    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage: java -cp out Client <host> <port> [provider]");
            return;
        }
        String host = args[0];
        int port;
        try {
            port = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.out.println("The port must be a number: " + args[1]);
            return;
        }
        boolean provider = args.length > 2 && args[2].equalsIgnoreCase("provider");

        ShopConnection conn = new ShopConnection();
        try {
            conn.connect(host, port);
        } catch (IOException e) {
            System.out.println("Cannot connect to " + host + ":" + port + ": " + e.getMessage());
            return;
        }
        System.out.println("Connected to " + host + ":" + port + " from " + conn.localEndpoint()
                + (provider ? " as the PROVIDER" : " as a client"));

        conn.start(
                message -> print(message),
                reason -> {                                 // the server is gone: end cleanly
                    synchronized (Client.class) {
                        System.out.println("\r*** " + reason + ". Goodbye.");
                    }
                    System.exit(0);
                });

        try {
            conn.send(provider ? "HELLO PROVIDER" : "HELLO CLIENT");
            BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));
            String line;
            prompt();
            while ((line = keyboard.readLine()) != null) {  // null: Ctrl+Z / Ctrl+D
                if (!line.isBlank()) conn.send(line);
                if (line.trim().equalsIgnoreCase("quit")) {
                    Thread.sleep(500);                      // give BYE time to arrive
                    break;
                }
                prompt();
            }
        } catch (IOException e) {
            print("*** cannot send: " + e.getMessage());
        } catch (InterruptedException ignored) {
        }
        conn.close();
        System.exit(0);
    }

    /** Prints a server message on its own line and shows the prompt again. */
    private static synchronized void print(String message) {
        System.out.print("\r" + message + System.lineSeparator() + "> ");
        System.out.flush();
    }

    private static synchronized void prompt() {
        System.out.print("> ");
        System.out.flush();
    }
}
