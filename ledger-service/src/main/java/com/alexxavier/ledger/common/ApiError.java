package com.alexxavier.ledger.common;

/** Error body returned by every endpoint: a stable machine-readable code plus a human message. */
public record ApiError(String error, String message) {
}
