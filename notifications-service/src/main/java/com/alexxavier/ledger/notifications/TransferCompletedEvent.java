package com.alexxavier.ledger.notifications;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Mirror of the ledger's event. Unknown fields are ignored so the producer can add fields safely. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferCompletedEvent(
        UUID eventId,
        UUID transactionId,
        UUID fromAccountId,
        UUID toAccountId,
        long amountCents,
        OffsetDateTime occurredAt) {
}
