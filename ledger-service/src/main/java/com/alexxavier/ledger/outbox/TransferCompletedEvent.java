package com.alexxavier.ledger.outbox;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Published to Kafka for every completed transaction (transfers and deposits).
 * {@code eventId} is unique per event, so consumers can deduplicate redeliveries.
 */
public record TransferCompletedEvent(
        UUID eventId,
        UUID transactionId,
        UUID fromAccountId,
        UUID toAccountId,
        long amountCents,
        OffsetDateTime occurredAt) {

    public static final String TYPE = "TransferCompleted";
}
