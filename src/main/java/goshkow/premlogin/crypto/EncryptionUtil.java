package goshkow.premlogin.crypto;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;

public final class EncryptionUtil {

    private EncryptionUtil() {
    }

    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("RSA is not available", exception);
        }
    }

    public static byte[] generateVerifyToken(SecureRandom random) {
        byte[] token = new byte[4];
        random.nextBytes(token);
        return token;
    }

    public static SecretKey decryptSharedKey(PrivateKey privateKey, byte[] encryptedSharedKey)
        throws NoSuchPaddingException, IllegalBlockSizeException, NoSuchAlgorithmException,
        BadPaddingException, InvalidKeyException {
        return new SecretKeySpec(decrypt(privateKey, encryptedSharedKey), "AES");
    }

    public static boolean verifyNonce(byte[] expected, PrivateKey privateKey, byte[] encryptedNonce)
        throws NoSuchPaddingException, IllegalBlockSizeException, NoSuchAlgorithmException,
        BadPaddingException, InvalidKeyException {
        byte[] decryptedNonce = decrypt(privateKey, encryptedNonce);
        if (decryptedNonce.length != expected.length) {
            return false;
        }

        for (int index = 0; index < expected.length; index++) {
            if (decryptedNonce[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    public static String getServerIdHashString(String serverId, SecretKey sharedSecret, PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
            digest.update(sharedSecret.getEncoded());
            digest.update(publicKey.getEncoded());
            return new BigInteger(digest.digest()).toString(16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 is not available", exception);
        }
    }

    private static byte[] decrypt(PrivateKey key, byte[] data)
        throws NoSuchPaddingException, NoSuchAlgorithmException, InvalidKeyException,
        IllegalBlockSizeException, BadPaddingException {
        Cipher cipher = Cipher.getInstance(key.getAlgorithm());
        cipher.init(Cipher.DECRYPT_MODE, key);
        return cipher.doFinal(data);
    }
}
