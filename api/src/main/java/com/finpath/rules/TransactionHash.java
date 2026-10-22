package com.finpath.rules;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Stable duplicate hint for a transaction (FR-04). SHA-256 over a versioned canonical string of
 * date, amount, direction, category code and mode. The user, note, tag, source, idempotency key
 * and timestamps are deliberately not part of it: editing a note or re-tagging must not make a row
 * look new, and uniqueness is always scoped by the user column in the index. The hash is a hint,
 * not a constraint: identical small payments on one day are legitimate. Pure: no clock, no database.
 */
public final class TransactionHash {

    public enum Direction { IN, OUT }

    static final String VERSION = "v1";

    private TransactionHash() {
    }

    /** 64-character lowercase hex SHA-256. Throws IllegalArgumentException on missing or invalid input. */
    public static String dedupeHash(LocalDate txnDate, long amountPaise, Direction direction, String categoryCode,
            String mode) {
        if (txnDate == null || direction == null) {
            throw new IllegalArgumentException("date and direction are required");
        }
        if (amountPaise <= 0) {
            throw new IllegalArgumentException("amountPaise must be positive");
        }
        String canonical = String.join("|", VERSION, txnDate.toString(), Long.toString(amountPaise),
                direction.name(), normalise(categoryCode, "categoryCode"), normalise(mode, "mode"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String normalise(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
