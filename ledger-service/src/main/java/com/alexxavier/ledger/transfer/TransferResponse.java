package com.alexxavier.ledger.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferResponse(
        UUID transactionId,
        LedgerTransaction.Status status,
        UUID fromAccountId,
        UUID toAccountId,
        long amountCents,
        String reason) {
}
