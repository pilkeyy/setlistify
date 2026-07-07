package com.setlistify.setlistify.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public class PkceUtil {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public static String generateCodeVerifier() {
        byte[] randomBytes = new byte[96];
        SECURE_RANDOM.nextBytes(randomBytes);

        // Base64URL encode without trailing '=' padding as mandated by RFC 7636
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /**
     * Applies a SHA-256 cryptographic hash to the verifier and Base64URL encodes the output
     * to generate the Code Challenge that Spotify requires upfront.
     */
    public static String generateCodeChallenge(String codeVerifier) {
        try {
            byte[] bytes = codeVerifier.getBytes(StandardCharsets.US_ASCII);
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hashedBytes = messageDigest.digest(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashedBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM does not support SHA-256 algorithm", e);
        }
    }

}

