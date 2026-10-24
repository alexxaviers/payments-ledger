package com.alexxavier.ledger.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Appends events to the outbox table. Must run inside the caller's transaction. */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TransferCompletedEvent transferCompleted(UUID transactionId, UUID from, UUID to, long amountCents) {
        var event = new TransferCompletedEvent(UUID.randomUUID(), transactionId, from, to, amountCents,
                OffsetDateTime.now());
        try {
            jdbc.update("INSERT INTO outbox (id, aggregate_id, event_type, payload) VALUES (?, ?, ?, ?::jsonb)",
                    event.eventId(), transactionId, TransferCompletedEvent.TYPE, json.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return event;
    }
}
