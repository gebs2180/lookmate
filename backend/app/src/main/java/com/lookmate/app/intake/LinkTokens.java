package com.lookmate.app.intake;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** A client's link token: 32 random bytes, base64url. Only its SHA-256 is stored, like identity's invites. */
final class LinkTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private LinkTokens() {
    }

    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Whether it could be a token at all: anything else is a 404 without a database lookup. */
    static boolean wellFormed(String token) {
        return token != null && SHAPE.matcher(token).matches();
    }

    static String hash(String token) {
        return sha256(token.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The client's page in the web app. */
    static String path(String token) {
        return "/m/" + token;
    }
}
