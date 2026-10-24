package com.alexxavier.ledger.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ships unpublished outbox rows to Kafka.
 *
 * <p>Each run claims up to {@code batchSize} rows with {@code FOR UPDATE SKIP LOCKED}, so several replicas can
 * publish in parallel without picking the same rows. It sends them, waits for Kafka to acknowledge every one,
 * and only then marks them published, all inside one database transaction.
 *
 * <p>This gives <b>at-least-once</b> delivery: if the process dies after Kafka acknowledged a message but before
 * the transaction commits, the rows stay unpublished and are sent again. Consumers must be idempotent.
 */
@Component
@ConditionalOnProperty(name = "ledger.outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final int batchSize;
    private final TransactionTemplate tx;

    public OutboxPublisher(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                           @Value("${ledger.outbox.topic}") String topic,
                           @Value("${ledger.outbox.publisher.batch-size:100}") int batchSize) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    record Row(UUID id, UUID aggregateId, String eventType, String payload) {
    }

    @Scheduled(fixedDelayString = "${ledger.outbox.publisher.interval:200ms}")
    public void tick() {
        try {
            int sent;
            do {
                sent = publishBatch();
            } while (sent == batchSize);   // drain a backlog quickly instead of one batch per tick
        } catch (Exception e) {
            log.warn("Outbox publish failed, will retry on the next tick: {}", e.toString());
        }
    }

    /** Claims, sends and marks one batch in a single database transaction. @return how many were published */
    public int publishBatch() {
        Integer published = tx.execute(status -> {
            try {
                return claimSendAndMark();
            } catch (Exception e) {
                status.setRollbackOnly();
                throw new IllegalStateException("outbox batch failed", e);
            }
        });
        return published == null ? 0 : published;
    }

    private int claimSendAndMark() throws Exception {
        List<Row> rows = jdbc.query("""
                SELECT id, aggregate_id, event_type, payload::text FROM outbox
                WHERE published_at IS NULL
                ORDER BY created_at
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """, (rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getString(3), rs.getString(4)), batchSize);
        if (rows.isEmpty()) {
            return 0;
        }

        List<CompletableFuture<SendResult<String, String>>> acks = new ArrayList<>(rows.size());
        for (Row row : rows) {
            var record = new ProducerRecord<>(topic, row.aggregateId().toString(), row.payload());
            record.headers().add("event_type", row.eventType().getBytes(StandardCharsets.UTF_8));
            record.headers().add("event_id", row.id().toString().getBytes(StandardCharsets.UTF_8));
            acks.add(kafka.send(record));
        }
        for (var ack : acks) {
            ack.get(15, TimeUnit.SECONDS);   // any failure rolls back: nothing is marked, everything is retried
        }

        Object[] ids = rows.stream().map(r -> r.id().toString()).toArray();
        jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ANY(?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids)));
        return rows.size();
    }
}
