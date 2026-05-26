package io.bunnycal.calendar.auth;

import io.bunnycal.calendar.config.CalendarSecurityProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class AesGcmTokenCipher implements TokenCipher {
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final String KEY_VERSION_PREFIX = "v1:";

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKey secretKey;

    public AesGcmTokenCipher(CalendarSecurityProperties properties) {
        byte[] decoded = Base64.getDecoder().decode(properties.getEncryptionKeyBase64());
        this.secretKey = new SecretKeySpec(decoded, "AES");
    }

    @Override
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(iv.length + encrypted.length)
                    .put(iv)
                    .put(encrypted)
                    .array();
            return KEY_VERSION_PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Token encryption failed", ex);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        try {
            String encoded = ciphertext;
            if (ciphertext != null && ciphertext.startsWith(KEY_VERSION_PREFIX)) {
                encoded = ciphertext.substring(KEY_VERSION_PREFIX.length());
            }
            byte[] payload = Base64.getDecoder().decode(encoded);
            ByteBuffer bb = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_BYTES];
            bb.get(iv);
            byte[] encrypted = new byte[bb.remaining()];
            bb.get(encrypted);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Token decryption failed", ex);
        }
    }
}
