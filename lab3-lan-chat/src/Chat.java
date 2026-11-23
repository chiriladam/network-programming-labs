import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A chat for the local network over UDP, without a server: every running copy is a peer.
 *
 *   java -cp out Chat <name> <port> <passphrase> [--broadcast] [--iface <ip>]
 *
 * Messages between peers (all are one datagram, see Message):
 *   JOIN    to everybody   "I want to join with this name"
 *   HERE    to the joiner  "I am online": the answer of every online peer to JOIN
 *   TAKEN   to the joiner  "this name is mine": the joiner gives up
 *   ONLINE  to everybody   "I have joined" (one second after JOIN, if the name was free)
 *   ALIVE   to everybody   every 5 s: "I am still here"
 *   LEAVE   to everybody   /quit
 *   MSG     to everybody   a message of the general channel
 *   PRIV    to one peer    a private message, encrypted
 *
 * Threads: the main thread reads the keyboard; one thread receives on each of
 * the two sockets; one timer thread sends ALIVE and removes silent peers.
 */
public final class Chat {

    static final int JOIN_WAIT_MS = 1_000;   // how long a new peer waits for HERE / TAKEN
    static final int ALIVE_EVERY_MS = 5_000; // heartbeat
    static final int GONE_AFTER_MS = 15_000; // a peer not heard from for this long is gone

    /** What we know about another peer. */
    static final class Peer {
        final String id;
        final String name;
        final InetSocketAddress endpoint;    // its private socket: where private messages go
        volatile long lastHeard;

        Peer(String id, String name, InetSocketAddress endpoint) {
            this.id = id;
            this.name = name;
            this.endpoint = endpoint;
            this.lastHeard = System.currentTimeMillis();
        }
    }

    private final String name;
    private final String id;                 // random for every run: recognises our own datagrams
    private final Net net;
    private final Crypto crypto;
    private final ChatLog log;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();   // name -> peer
    private final AtomicLong seq = new AtomicLong();
    private final Set<String> recent = Collections.newSetFromMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
            return size() > 4096;            // remember the last 4096 (sender, seq) pairs
        }
    });
    private volatile boolean online;         // false while joining
    private volatile String takenBy;         // set if somebody answered TAKEN

    Chat(String name, Net net, Crypto crypto, ChatLog log) {
        this.name = name;
        this.net = net;
        this.crypto = crypto;
        this.log = log;
        byte[] r = new byte[8];
        new SecureRandom().nextBytes(r);
        this.id = HexFormat.of().formatHex(r);
    }

    // =================================================================== start

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java -cp out Chat <name> <port> <passphrase> [--broadcast] [--iface <ip>]");
            return;
        }
        String name = args[0];
        if (!Message.validName(name)) {
            System.out.println("The name must be 1-20 letters, digits, '_' or '-'.");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(args[1]);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            System.out.println("The port must be a number from 1 to 65535.");
            return;
        }
        String passphrase = args[2];
        boolean broadcast = false;
        String ifaceIp = null;
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--broadcast")) broadcast = true;
            else if (args[i].equals("--iface") && i + 1 < args.length) ifaceIp = args[++i];
        }

        ChatLog log = new ChatLog("chat-" + name + ".log");
        Crypto crypto = new Crypto(passphrase, port);          // derive the key (takes a moment)
        Net net;
        try {
            net = new Net(port, broadcast, ifaceIp, log);
        } catch (IOException e) {
            System.out.println("Cannot open the sockets: " + e.getMessage());
            return;
        }
        System.out.println("Network card: " + net.iface.getDisplayName() + " (" + net.localAddress.getHostAddress()
                + "), general channel by " + net.everybodyDescription()
                + ", my endpoint " + endpoint(net.privateEndpoint()));
        log.note("name " + name + ", " + net.everybodyDescription() + ", private " + endpoint(net.privateEndpoint()));

        Chat chat = new Chat(name, net, crypto, log);
        chat.run();
    }

    private void run() throws IOException {
        startReceiver(net.groupSocket, "receive-group");
        startReceiver(net.privateSocket, "receive-private");

        // ---- join: tell everybody, then wait one second for HERE / TAKEN
        net.sendToAll(message("JOIN", ""));
        sleep(JOIN_WAIT_MS);
        if (takenBy != null) {
            System.out.println("The name '" + name + "' is already in use (by " + takenBy + "). Choose another name.");
            log.note("name taken, not joining");
            net.close();
            System.exit(1);
        }
        online = true;
        net.sendToAll(message("ONLINE", ""));
        print("* You joined the chat as " + name + ". Online: " + onlineList()
                + ". Commands: /msg name text, /users, /quit");

        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "timer");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::sendAlive, ALIVE_EVERY_MS, ALIVE_EVERY_MS, TimeUnit.MILLISECONDS);
        timer.scheduleAtFixedRate(this::removeSilentPeers, 1, 1, TimeUnit.SECONDS);

        // ---- the keyboard
        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = keyboard.readLine()) != null) {      // print() always shows the prompt again
            line = line.strip();
            if (line.isEmpty()) {
                prompt();
            } else if (!command(line)) {
                break;
            }
        }
        quit();
    }

    // =================================================================== the user's commands

    /** Returns false when the user leaves. */
    private boolean command(String line) {
        try {
            if (!line.startsWith("/")) {
                sendGeneral(line);
            } else if (line.equals("/quit")) {
                return false;
            } else if (line.equals("/users")) {
                print(usersTable());
            } else if (line.startsWith("/msg ")) {
                String[] p = line.split("\\s+", 3);
                if (p.length < 3) print("! Use: /msg name text");
                else sendPrivate(p[1], p[2]);
            } else {
                print("! Unknown command. Commands: text, /msg name text, /users, /quit");
            }
        } catch (IOException | GeneralSecurityException e) {
            print("! Could not send: " + e.getMessage());
        }
        return true;
    }

    private void sendGeneral(String text) throws IOException {
        if (text.length() > Message.MAX_TEXT) {
            print("! A message has at most " + Message.MAX_TEXT + " characters (yours has " + text.length() + ").");
            return;
        }
        net.sendToAll(message("MSG", text));
        print("[general] " + name + ": " + text);            // shown once; the echo is dropped
    }

    private void sendPrivate(String to, String text) throws IOException, GeneralSecurityException {
        if (text.length() > Message.MAX_TEXT) {
            print("! A message has at most " + Message.MAX_TEXT + " characters.");
            return;
        }
        if (to.equals(name)) {
            print("! That is you.");
            return;
        }
        Peer p = peers.get(to);
        if (p == null) {
            print("! " + to + " is not online. /users shows who is.");
            return;
        }
        String encrypted = crypto.encrypt(text, privateAad(id, name, to));
        net.send(message("PRIV", to + ":" + encrypted), p.endpoint);     // to that peer only
        print("[private] to " + to + ": " + text);
    }

    private void quit() {
        try {
            net.sendToAll(message("LEAVE", ""));
        } catch (IOException ignored) {
        }
        print("* You left the chat.");
        log.note("quit");
        net.close();
        log.close();
        System.exit(0);
    }

    // =================================================================== receiving

    private void startReceiver(DatagramSocket socket, String threadName) {
        Thread t = new Thread(() -> {
            byte[] buffer = new byte[65_535];                 // the largest possible datagram
            while (!socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);                   // one datagram = one message
                } catch (IOException e) {
                    break;                                    // socket closed by /quit
                }
                InetSocketAddress from = (InetSocketAddress) packet.getSocketAddress();
                try {
                    handle(Message.parse(packet.getData(), packet.getLength()), from, packet.getLength());
                } catch (Message.Invalid e) {
                    log.dropped(from, e.getMessage(), packet.getLength());
                } catch (RuntimeException e) {
                    log.dropped(from, "error: " + e, packet.getLength());  // never crash
                }
            }
        }, threadName);
        t.setDaemon(true);
        t.start();
    }

    private void handle(Message m, InetSocketAddress from, int size) {
        if (m.senderId.equals(id)) {                          // our own datagram came back
            log.dropped(from, "own " + m.type, size);
            return;
        }
        synchronized (recent) {
            if (!recent.add(m.senderId + ":" + m.seq)) {      // already seen (e.g. via two paths)
                log.dropped(from, "duplicate " + m.type, size);
                return;
            }
        }
        log.received(from, m.type, size);

        Peer known = peers.get(m.senderName);
        if (known != null && known.id.equals(m.senderId)) known.lastHeard = System.currentTimeMillis();

        switch (m.type) {
            case "JOIN" -> onJoin(m, from);
            case "HERE" -> addPeer(m, from, false);
            case "TAKEN" -> {
                if (!online && m.payload.equals(name)) takenBy = m.senderName + " at " + endpoint(from);
            }
            case "ONLINE", "ALIVE" -> addPeer(m, from, true);
            case "LEAVE" -> {
                if (known != null && known.id.equals(m.senderId)) {
                    peers.remove(m.senderName);
                    if (online) print("* " + m.senderName + " left the chat");
                }
            }
            case "MSG" -> {
                addPeer(m, from, true);
                if (online) print("[general] " + m.senderName + ": " + clean(m.payload));
            }
            case "PRIV" -> onPrivate(m, from, size);
            default -> { }
        }
    }

    /** A new peer wants to join. */
    private void onJoin(Message m, InetSocketAddress from) {
        if (m.senderName.equals(name)) {
            // Same name as ours. Online: the name is ours. Both still joining: the smaller id keeps it.
            if (online || id.compareTo(m.senderId) < 0) send(message("TAKEN", name), from);
            return;
        }
        if (online) send(message("HERE", ""), from);         // answer at once: "I am online"
    }

    /** Adds or refreshes a peer; announce = show "joined" if it is new. */
    private void addPeer(Message m, InetSocketAddress from, boolean announce) {
        // every datagram is sent from the peer's private socket, so `from` is its private endpoint
        Peer known = peers.get(m.senderName);
        if (known != null && known.id.equals(m.senderId)) return;
        peers.put(m.senderName, new Peer(m.senderId, m.senderName, from));   // new, or same name restarted
        if (announce && online) print("* " + m.senderName + " joined the chat");
    }

    private void onPrivate(Message m, InetSocketAddress from, int size) {
        String[] p = m.payload.split(":", 2);
        if (p.length != 2 || !p[0].equals(name)) {
            log.dropped(from, "private message not for me", size);
            return;
        }
        String text;
        try {
            text = crypto.decrypt(p[1], privateAad(m.senderId, m.senderName, name));
        } catch (GeneralSecurityException e) {
            log.dropped(from, "cannot decrypt (other passphrase, or changed)", size);
            return;
        }
        if (text.length() > Message.MAX_TEXT) {
            log.dropped(from, "private text too long", size);
            return;
        }
        addPeer(m, from, true);
        print("[private] " + m.senderName + ": " + clean(text));
    }

    /** Authenticated but not encrypted: who sent it, and to whom. */
    private static String privateAad(String senderId, String from, String to) {
        return "PRIV|" + senderId + "|" + from + "|" + to;
    }

    // =================================================================== timer

    private void sendAlive() {
        try {
            net.sendToAll(message("ALIVE", ""));
        } catch (IOException e) {
            log.note("cannot send ALIVE: " + e.getMessage());
        }
    }

    private void removeSilentPeers() {
        long now = System.currentTimeMillis();
        for (Peer p : peers.values()) {
            if (now - p.lastHeard > GONE_AFTER_MS && peers.remove(p.name, p)) {
                print("* " + p.name + " left the chat (not heard from for " + GONE_AFTER_MS / 1000 + " s)");
                log.note(p.name + " timed out");
            }
        }
    }

    // =================================================================== helpers

    private Message message(String type, String payload) {
        return new Message(type, id, name, seq.incrementAndGet(), payload);
    }

    private void send(Message m, InetSocketAddress to) {
        try {
            net.send(m, to);
        } catch (IOException e) {
            log.note("cannot send " + m.type + ": " + e.getMessage());
        }
    }

    private String onlineList() {
        List<String> names = new ArrayList<>(peers.keySet());
        Collections.sort(names);
        return names.isEmpty() ? "nobody else yet" : String.join(", ", names);
    }

    private String usersTable() {
        StringBuilder sb = new StringBuilder("Online (" + (peers.size() + 1) + "):");
        sb.append(System.lineSeparator()).append(String.format("  %-20s %s (you)", name, endpoint(net.privateEndpoint())));
        List<Peer> list = new ArrayList<>(peers.values());
        list.sort((a, b) -> a.name.compareTo(b.name));
        for (Peer p : list) {
            sb.append(System.lineSeparator()).append(String.format("  %-20s %s", p.name, endpoint(p.endpoint)));
        }
        return sb.toString();
    }

    static String endpoint(InetSocketAddress a) {
        return a.getAddress().getHostAddress() + ":" + a.getPort();
    }

    /** Received text is shown without control characters (they could mess up the console). */
    private static String clean(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) sb.append(Character.isISOControl(c) ? '?' : c);
        return sb.toString();
    }

    private static synchronized void print(String text) {
        System.out.print("\r" + text + System.lineSeparator() + "> ");
        System.out.flush();
    }

    private static synchronized void prompt() {
        System.out.print("> ");
        System.out.flush();
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
