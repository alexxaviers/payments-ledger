package com.alexxavier.ledger.transfer;

/**
 * What the API returns for a transfer: the body, the HTTP status, and whether it was replayed from the
 * idempotency store rather than executed now.
 */
public record TransferResult(TransferResponse body, int httpStatus, boolean replayed) {
}
