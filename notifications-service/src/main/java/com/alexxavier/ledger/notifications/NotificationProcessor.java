package com.alexxavier.ledger.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Idempotent event handler. Recording the event id and performing the side effect happen in one database
 * transaction, so an event is either fully processed exactly once or not at all.
 *
 * <p>At-least-once delivery (outbox + Kafka) plus this idempotent consumer = effectively-once processing.
 */
@Service
public class NotificationProcessor {

    /** The ledger's EXTERNAL system account: deposits come from it, nobody to notify. */
    static final UUID EXTERNAL = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final JdbcTemplate jdbc;

    public NotificationProcessor(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if the event was processed now, false if it had already been processed (duplicate) */
    @Transactional
    public boolean process(TransferCompletedEvent event) {
        jdbc.update("INSERT INTO deliveries (event_id) VALUES (?)", event.eventId());

        // If another consumer is processing the same event right now, this waits for it; once it commits we get
        // 0 rows and skip. This single statement is the whole deduplication mechanism.
        int inserted = jdbc.update(
                "INSERT INTO processed_events (event_id, transaction_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                event.eventId(), event.transactionId());
        if (inserted == 0) {
            return false;
        }

        String amount = String.format("$%d.%02d", event.amountCents() / 100, event.amountCents() % 100);
        if (!EXTERNAL.equals(event.fromAccountId())) {
            notify(event, event.fromAccountId(), "You sent " + amount + " (transaction " + event.transactionId() + ")");
        }
        notify(event, event.toAccountId(), "You received " + amount + " (transaction " + event.transactionId() + ")");
        return true;
    }

    private void notify(TransferCompletedEvent event, UUID accountId, String message) {
        jdbc.update("INSERT INTO notifications (event_id, account_id, message) VALUES (?, ?, ?)",
                event.eventId(), accountId, message);
    }
}
