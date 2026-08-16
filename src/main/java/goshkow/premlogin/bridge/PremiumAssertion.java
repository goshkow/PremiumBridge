package goshkow.premlogin.bridge;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.UUID;

/**
 * A short-lived, one-player assertion signed by the trusted Velocity proxy.
 * The wire format is deliberately independent from Bukkit and Velocity APIs.
 */
public record PremiumAssertion(
    String username,
    UUID premiumUuid,
    boolean premium,
    long issuedAt,
    long expiresAt,
    String nonce,
    String signature
) {

    private static final int FORMAT_VERSION = 1;
    private static final int MAX_PAYLOAD_BYTES = 4096;
    private static final String MAGIC = "PBRIDGE";

    public static PremiumAssertion create(String username, UUID premiumUuid, long ttlMillis, String secret) {
        long issuedAt = System.currentTimeMillis();
        long expiresAt = issuedAt + Math.max(1000L, ttlMillis);
        String nonce = UUID.randomUUID().toString();
        String signature = sign(canonical(username, premiumUuid, true, issuedAt, expiresAt, nonce), secret);
        return new PremiumAssertion(username, premiumUuid, true, issuedAt, expiresAt, nonce, signature);
    }

    public boolean isValid(String secret, long now) {
        if (!premium || username == null || username.isBlank() || premiumUuid == null
            || nonce == null || nonce.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }

        if (issuedAt > now + 5000L || expiresAt < now || expiresAt <= issuedAt) {
            return false;
        }

        String expected = sign(canonical(username, premiumUuid, premium, issuedAt, expiresAt, nonce), secret);
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            signature.getBytes(StandardCharsets.US_ASCII)
        );
    }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF(MAGIC);
            output.writeInt(FORMAT_VERSION);
            output.writeUTF(username);
            output.writeUTF(premiumUuid.toString());
            output.writeBoolean(premium);
            output.writeLong(issuedAt);
            output.writeLong(expiresAt);
            output.writeUTF(nonce);
            output.writeUTF(signature);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to encode PremiumBridge assertion", exception);
        }
    }

    public static PremiumAssertion decode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_PAYLOAD_BYTES) {
            return null;
        }

        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
            if (!MAGIC.equals(input.readUTF()) || input.readInt() != FORMAT_VERSION) {
                return null;
            }

            String username = input.readUTF();
            UUID uuid = UUID.fromString(input.readUTF());
            boolean premium = input.readBoolean();
            long issuedAt = input.readLong();
            long expiresAt = input.readLong();
            String nonce = input.readUTF();
            String signature = input.readUTF();
            return new PremiumAssertion(username, uuid, premium, issuedAt, expiresAt, nonce, signature);
        } catch (IOException | IllegalArgumentException exception) {
            return null;
        }
    }

    private static String canonical(
        String username,
        UUID uuid,
        boolean premium,
        long issuedAt,
        long expiresAt,
        String nonce
    ) {
        return MAGIC + "\n" + FORMAT_VERSION + "\n" + username + "\n" + uuid + "\n"
            + premium + "\n" + issuedAt + "\n" + expiresAt + "\n" + nonce;
    }

    private static String sign(String value, String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("PremiumBridge shared secret is empty");
        }

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException exception) {
            throw new IllegalStateException("HMAC-SHA256 is not available", exception);
        }
    }
}
