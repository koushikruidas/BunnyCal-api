package io.bunnycal.calendar.auth;

import io.bunnycal.calendar.config.CalendarSecurityProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AesGcmTokenCipher implements TokenCipher {
    private static final Logger log = LoggerFactory.getLogger(AesGcmTokenCipher.class);
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final String KEY_VERSION_PREFIX = "v1:";
    private static final Set<Integer> VALID_AES_KEY_BYTES = Set.of(16, 24, 32);

    private final SecureRandom secureRandom = new SecureRandom();
    private final SecretKey secretKey;

    public AesGcmTokenCipher(CalendarSecurityProperties properties) {
        String rawKeyString = properties.getEncryptionKeyBase64();
        log.info("[KEY-DIAG] raw key string  : {}", rawKeyString);
        log.info("[KEY-DIAG] raw string length: {}", rawKeyString == null ? "null" : rawKeyString.length());

        if (rawKeyString == null || rawKeyString.isBlank()) {
            throw new IllegalStateException(
                "calendar.security.encryption-key-base64 is blank — set CALENDAR_TOKEN_ENCRYPTION_KEY_BASE64 to a base64-encoded 32-byte AES-256 key (generate: openssl rand -base64 32)");
        }

        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(rawKeyString.strip());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                "calendar.security.encryption-key-base64 is not valid Base64. raw value: [" + rawKeyString + "]", ex);
        }
        log.info("[KEY-DIAG] decoded byte length: {}", decoded.length);

        if (!VALID_AES_KEY_BYTES.contains(decoded.length)) {
            throw new IllegalStateException(
                "calendar.security.encryption-key-base64 decoded to " + decoded.length
                + " bytes — AES requires 16, 24, or 32 bytes. "
                + "Source env var: CALENDAR_TOKEN_ENCRYPTION_KEY_BASE64. "
                + "Generate a correct key with: openssl rand -base64 32");
        }

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
