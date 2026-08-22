package com.campusgrid.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Thread-safe authentication utility for SHA-256 password hashing and verification.
 * <p>
 * This class is stateless and headless. It uses one {@link MessageDigest} instance
 * per call, which keeps operations safe for concurrent usage across threads.
 */
public final class AuthGateway {

    private static final char[] HEX_ALPHABET = "0123456789abcdef".toCharArray();

    private AuthGateway() {
    }

    /**
     * Hashes the input password with SHA-256 and returns a lowercase hex string.
     *
     * @param password plain-text password
     * @return SHA-256 digest encoded as lowercase hexadecimal
     */
    public static String hashPassword(String password) {
        if (password == null) {
            throw new IllegalArgumentException("password must not be null.");
        }

        MessageDigest digest = newSha256Digest();
        byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }

    /**
     * Authenticates a password attempt against a stored SHA-256 hex hash.
     *
     * @param attempt plain-text password attempt
     * @param storedHash expected SHA-256 hex hash
     * @return true if the attempt matches the stored hash; otherwise false
     */
    public static boolean authenticate(String attempt, String storedHash) {
        if (attempt == null || storedHash == null) {
            return false;
        }
        String computedHash = hashPassword(attempt);
        return constantTimeEquals(computedHash, storedHash);
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new RuntimeException("SHA-256 algorithm is unavailable.", ex);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        char[] output = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xFF;
            output[i * 2] = HEX_ALPHABET[value >>> 4];
            output[(i * 2) + 1] = HEX_ALPHABET[value & 0x0F];
        }
        return new String(output);
    }

    private static boolean constantTimeEquals(String left, String right) {
        int leftLength = left.length();
        int rightLength = right.length();
        int maxLength = leftLength > rightLength ? leftLength : rightLength;

        int diff = leftLength ^ rightLength;
        for (int i = 0; i < maxLength; i++) {
            char leftChar = i < leftLength ? left.charAt(i) : 0;
            char rightChar = i < rightLength ? right.charAt(i) : 0;
            diff |= leftChar ^ rightChar;
        }
        return diff == 0;
    }
}
