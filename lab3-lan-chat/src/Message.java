import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * One datagram of the chat. Every datagram is one line of UTF-8 text:
 *
 *     LANCHAT1|TYPE|senderId|senderName|seq|payload
 *
 *   LANCHAT1    magic word: a datagram that does not start with it is not ours
 *   TYPE        JOIN, HERE, TAKEN, ONLINE, ALIVE, LEAVE, MSG or PRIV (see Chat)
 *   senderId    16 hex digits, random for every run of the program
 *   senderName  the user's name: 1-20 letters, digits, '_' or '-'
 *   seq         the sender's message counter (to drop duplicates)
 *   payload     the rest; may contain '|'. MSG: the text. PRIV: "toName:base64".
 *
 * With UDP one datagram is one message, so no delimiter or length prefix is
 * needed: the datagram itself is the boundary.
 */
public final class Message {

    public static final String MAGIC = "LANCHAT1";
    public static final int MAX_TEXT = 500;          // characters per message
    public static final int MAX_DATAGRAM = 4096;     // bytes; larger datagrams are dropped
    public static final Set<String> TYPES =
            Set.of("JOIN", "HERE", "TAKEN", "ONLINE", "ALIVE", "LEAVE", "MSG", "PRIV");

    public final String type;
    public final String senderId;
    public final String senderName;
    public final long seq;
    public final String payload;

    public Message(String type, String senderId, String senderName, long seq, String payload) {
        this.type = type;
        this.senderId = senderId;
        this.senderName = senderName;
        this.seq = seq;
        this.payload = payload;
    }

    public byte[] toBytes() {
        String s = MAGIC + "|" + type + "|" + senderId + "|" + senderName + "|" + seq + "|" + payload;
        return s.getBytes(StandardCharsets.UTF_8);
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,20}");
    }

    /** Thrown for a datagram that is not a valid message of our chat. */
    public static final class Invalid extends Exception {
        public Invalid(String reason) {
            super(reason);
        }
    }

    /** Checks every field; throws Invalid with the reason if anything is wrong. */
    public static Message parse(byte[] data, int length) throws Invalid {
        if (length > MAX_DATAGRAM) throw new Invalid("too long (" + length + " bytes)");
        String s = new String(data, 0, length, StandardCharsets.UTF_8);
        String[] f = s.split("\\|", 6);                 // the payload may contain '|'
        if (f.length != 6 || !f[0].equals(MAGIC)) throw new Invalid("not a chat message");
        if (!TYPES.contains(f[1])) throw new Invalid("unknown type");
        if (!f[2].matches("[0-9a-f]{16}")) throw new Invalid("bad sender id");
        if (!validName(f[3])) throw new Invalid("bad name");
        long seq;
        try {
            seq = Long.parseLong(f[4]);
        } catch (NumberFormatException e) {
            throw new Invalid("bad sequence number");
        }
        if (f[1].equals("MSG") && (f[5].isEmpty() || f[5].length() > MAX_TEXT)) {
            throw new Invalid("text empty or longer than " + MAX_TEXT);
        }
        return new Message(f[1], f[2], f[3], seq, f[5]);
    }
}
