package com.alexxavier.ledger.transfer;

/** The same Idempotency-Key was sent with a different request body. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String key) {
        super("Idempotency-Key '" + key + "' was already used with a different request body");
    }
}
