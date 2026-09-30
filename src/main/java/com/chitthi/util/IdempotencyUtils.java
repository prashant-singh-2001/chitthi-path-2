package com.chitthi.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class IdempotencyUtils {

    private IdempotencyUtils() {}

    /**
     * Compute SHA-256 hex string for a given text.
     */
    public static String sha256Hex(String text) {
        if (text == null) {
            return "empty";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Builds a deterministic idempotency key for stage task tracking.
     * Format: {stage}:{documentId}:{pageNo}:{contentHash}
     */
    public static String buildKey(String stage, UUID documentId, Integer pageNo, String contentHash) {
        String s = stage != null ? stage.toUpperCase() : "UNKNOWN";
        String doc = documentId != null ? documentId.toString() : "none";
        String page = pageNo != null ? pageNo.toString() : "all";
        String hash = (contentHash != null && !contentHash.isBlank()) ? contentHash : "none";
        return s + ":" + doc + ":" + page + ":" + hash;
    }
}
