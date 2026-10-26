package com.example.btl_iot.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

// Device tokens are 256-bit random values, so a fast unsalted hash is sufficient (unlike PINs/passwords).
public final class DeviceTokens {
    private DeviceTokens() {}
    public static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static boolean matches(String token, String expectedHex) {
        if (token == null || expectedHex == null) return false;
        return MessageDigest.isEqual(sha256Hex(token).getBytes(StandardCharsets.US_ASCII),
                expectedHex.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }
    public static boolean validId(String id) { return id != null && id.matches("[a-z0-9][a-z0-9-]{1,39}"); }
    public static boolean validHash(String hex) { return hex != null && hex.matches("[0-9a-fA-F]{64}"); }
}
