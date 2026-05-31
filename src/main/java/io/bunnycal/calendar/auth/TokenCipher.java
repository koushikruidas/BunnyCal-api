package io.bunnycal.calendar.auth;

public interface TokenCipher {
    String encrypt(String plaintext);

    String decrypt(String ciphertext);
}
