package com.alexxavier.ledger.notifications;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(properties = {
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"})
class IdempotentConsumerTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    NotificationProcessor processor;

    @Value("${notifications.topic}")
    String topic;

    @Test
    void duplicateDeliveriesAreProcessedOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        String payload = """
                {"eventId":"%s","transactionId":"%s","fromAccountId":"%s","toAccountId":"%s",
                 "amountCents":1250,"occurredAt":"2026-10-24T12:00:00Z"}
                """.formatted(eventId, txId, UUID.randomUUID(), UUID.randomUUID());

        // simulate the outbox publisher crashing after sending: the same event arrives three times
        for (int i = 0; i < 3; i++) {
            kafka.send(topic, txId.toString(), payload).get();
        }

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(count("SELECT COUNT(*) FROM deliveries WHERE event_id = ?", eventId)).isEqualTo(3));
        assertThat(count("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", eventId)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE event_id = ?", eventId)).isEqualTo(2);
    }

    @Test
    void depositsNotifyOnlyTheReceiver() {
        var event = new TransferCompletedEvent(UUID.randomUUID(), UUID.randomUUID(),
                NotificationProcessor.EXTERNAL, UUID.randomUUID(), 500, null);

        assertThat(processor.process(event)).isTrue();
        assertThat(processor.process(event)).isFalse();
        assertThat(count("SELECT COUNT(*) FROM notifications WHERE event_id = ?", event.eventId())).isEqualTo(1);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }
}
