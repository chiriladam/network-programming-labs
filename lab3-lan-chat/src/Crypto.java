import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Task 4: encryption of the private messages.
 *
 *  Key: PBKDF2 with HMAC-SHA256, 200 000 iterations, from the passphrase.
 *       Every peer must get the same key without talking to the others, so
 *       the salt is fixed: "lan-chat/" + the port of the chat (public, but
 *       different for every chat, so a table precomputed for one chat does not
 *       work for another). The slow derivation makes guessing passphrases
 *       expensive; the result is a uniform 256-bit AES key.
 *
 *  Cipher: AES-256-GCM. A new random 12-byte nonce from SecureRandom for every
 *       message, sent in front of the ciphertext. GCM adds a 16-byte tag: a
 *       message changed by anybody who does not have the key fails to decrypt.
 *       The sender's id and name and the receiver's name are "associated data":
 *       not encrypted, but protected by the tag, so they cannot be changed
 *       either (e.g. to redirect a message to somebody else).
 */
public final class Crypto {

    private static final int ITERATIONS = 200_000;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public Crypto(String passphrase, int port) throws GeneralSecurityException {
        byte[] salt = ("lan-chat/" + port).getBytes(StandardCharsets.UTF_8);
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256);
        byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        spec.clearPassword();
        key = new SecretKeySpec(k, "AES");
    }

    /** Returns base64(nonce + ciphertext + tag). */
    public String encrypt(String text, String associatedData) throws GeneralSecurityException {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);                         // never the same nonce twice
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
        c.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(nonce.length + ct.length).put(nonce).put(ct).array());
    }

    /** Throws GeneralSecurityException if the key is wrong or anything was changed. */
    public String decrypt(String base64, String associatedData) throws GeneralSecurityException {
        byte[] all;
        try {
            all = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("not base64");
        }
        if (all.length < NONCE_BYTES + TAG_BITS / 8) throw new GeneralSecurityException("too short");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, NONCE_BYTES));
        c.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        byte[] pt = c.doFinal(all, NONCE_BYTES, all.length - NONCE_BYTES);
        return new String(pt, StandardCharsets.UTF_8);
    }
}
