package com.alexxavier.ledger.transfer;

import com.alexxavier.ledger.account.AccountBalance;
import com.alexxavier.ledger.account.AccountBalanceRepository;
import com.alexxavier.ledger.account.AccountNotFoundException;
import com.alexxavier.ledger.outbox.OutboxWriter;
import com.alexxavier.ledger.transfer.IdempotencyStore.StoredResponse;
import com.alexxavier.ledger.transfer.LedgerTransaction.Status;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The transactional core of a transfer. Everything here commits together or not at all.
 * Callers go through {@link TransferService}, which handles the lost-race case on idempotency keys.
 */
@Component
class TransferExecutor {

    static final int HTTP_CREATED = 201;
    static final int HTTP_UNPROCESSABLE = 422;

    private final IdempotencyStore idempotency;
    private final AccountBalanceRepository balances;
    private final LedgerTransactionRepository transactions;
    private final LedgerEntryRepository entries;
    private final OutboxWriter outbox;
    private final ObjectMapper json;

    TransferExecutor(IdempotencyStore idempotency, AccountBalanceRepository balances,
                     LedgerTransactionRepository transactions, LedgerEntryRepository entries,
                     OutboxWriter outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.idempotency = idempotency;
        this.balances = balances;
        this.transactions = transactions;
        this.entries = entries;
        this.json = json;
    }

    @Transactional
    public TransferResult execute(String idempotencyKey, String requestHash, TransferRequest req,
                                  boolean sourceMayGoNegative) {
        // 1. Idempotency: have we seen this key before? Then answer exactly as we did the first time.
        var existing = idempotency.find(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), idempotencyKey, requestHash);
        }

        // 2. Lock BOTH balance rows, always in the same order (sorted by id).
        //    If A->B and B->A run at once, both try to lock min(A,B) first, so one simply waits for the
        //    other instead of each holding one lock and waiting forever for the second (a deadlock).
        Map<UUID, AccountBalance> locked = lockInOrder(req.fromAccountId(), req.toAccountId());
        AccountBalance from = locked.get(req.fromAccountId());
        AccountBalance to = locked.get(req.toAccountId());

        TransferResponse response;
        int httpStatus;
        long amount = req.amountCents();

        // 3. Business rule: no overdrafts (the CHECK constraint in the schema is the second safety net).
        if (!sourceMayGoNegative && from.getBalanceCents() < amount) {
            LedgerTransaction tx = transactions.save(
                    new LedgerTransaction(UUID.randomUUID(), idempotencyKey, Status.REJECTED));
            response = new TransferResponse(tx.getId(), Status.REJECTED, req.fromAccountId(), req.toAccountId(),
                    amount, "INSUFFICIENT_FUNDS");
            httpStatus = HTTP_UNPROCESSABLE;
        } else {
            // 4. Write the transfer: one transaction, two balancing entries, two cached balances.
            LedgerTransaction tx = transactions.save(
                    new LedgerTransaction(UUID.randomUUID(), idempotencyKey, Status.COMPLETED));
            entries.save(new LedgerEntry(tx.getId(), req.fromAccountId(), -amount));
            entries.save(new LedgerEntry(tx.getId(), req.toAccountId(), amount));
            from.debit(amount);
            to.credit(amount);
            // The event is written to the outbox in this same transaction: transfer and event are atomic.
            outbox.transferCompleted(tx.getId(), req.fromAccountId(), req.toAccountId(), amount);
            response = new TransferResponse(tx.getId(), Status.COMPLETED, req.fromAccountId(), req.toAccountId(),
                    amount, null);
            httpStatus = HTTP_CREATED;
        }

        // Push the JPA writes to the database now, so constraint violations (e.g. a concurrent request that
        // already committed the same idempotency key) surface here as DataIntegrityViolationException.
        transactions.flush();

        // 5. Store the response under the idempotency key; it commits atomically with the transfer.
        if (!idempotency.insertIfAbsent(idempotencyKey, requestHash, toJson(response), httpStatus)) {
            // Someone else committed this key while we were working. Roll back; the caller replays theirs.
            throw new LostIdempotencyRaceException(idempotencyKey);
        }
        return new TransferResult(response, httpStatus, false);
    }

    @Transactional(readOnly = true)
    public TransferResult replayStored(String idempotencyKey, String requestHash) {
        return idempotency.find(idempotencyKey)
                .map(stored -> replay(stored, idempotencyKey, requestHash))
                .orElse(null);
    }

    private Map<UUID, AccountBalance> lockInOrder(UUID a, UUID b) {
        var ordered = Stream.of(a, b).sorted().toList();
        AccountBalance first = balances.lockById(ordered.get(0))
                .orElseThrow(() -> new AccountNotFoundException(ordered.get(0)));
        AccountBalance second = balances.lockById(ordered.get(1))
                .orElseThrow(() -> new AccountNotFoundException(ordered.get(1)));
        return Map.of(first.getAccountId(), first, second.getAccountId(), second);
    }

    private TransferResult replay(StoredResponse stored, String key, String requestHash) {
        if (!stored.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException(key);   // same key, different body -> 422
        }
        try {
            return new TransferResult(json.readValue(stored.responseBody(), TransferResponse.class),
                    stored.statusCode(), true);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt stored response for key " + key, e);
        }
    }

    private String toJson(TransferResponse response) {
        try {
            return json.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    static class LostIdempotencyRaceException extends RuntimeException {
        LostIdempotencyRaceException(String key) {
            super("Concurrent request committed idempotency key " + key + " first");
        }
    }
}
