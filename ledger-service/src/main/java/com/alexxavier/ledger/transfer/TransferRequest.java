package com.alexxavier.ledger.transfer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @Positive Long amountCents) {

    /**
     * Fingerprint of the request body, stored next to the idempotency key so that reusing a key with a
     * different body is detected instead of silently replaying the wrong answer.
     */
    String fingerprint(String kind) {
        String canonical = kind + "|" + fromAccountId + "|" + toAccountId + "|" + amountCents;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
