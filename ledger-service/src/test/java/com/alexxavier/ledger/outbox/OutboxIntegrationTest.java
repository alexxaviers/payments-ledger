package com.alexxavier.ledger.outbox;

import com.alexxavier.ledger.AbstractIntegrationTest;
import com.alexxavier.ledger.account.AccountDtos.CreateAccountRequest;
import com.alexxavier.ledger.account.AccountService;
import com.alexxavier.ledger.transfer.TransferRequest;
import com.alexxavier.ledger.transfer.TransferResult;
import com.alexxavier.ledger.transfer.TransferService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class OutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    TransferService transfers;

    @Autowired
    AccountService accounts;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper json;

    @Value("${ledger.outbox.topic}")
    String topic;

    @Test
    void completedTransferIsPublishedToKafka() throws Exception {
        UUID from = account(5_000);
        UUID to = account(0);

        try (var consumer = consumer()) {
            TransferResult result = transfers.transfer(UUID.randomUUID().toString(),
                    new TransferRequest(from, to, 1_234L));
            UUID txId = result.body().transactionId();

            await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                    assertThat(outboxRows(txId, "published_at IS NOT NULL")).isEqualTo(1));

            JsonNode event = null;
            long deadline = System.currentTimeMillis() + 30_000;
            while (event == null && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(500))) {
                    JsonNode node = json.readTree(rec.value());
                    if (node.get("transactionId").asText().equals(txId.toString())) {
                        assertThat(rec.key()).isEqualTo(txId.toString());
                        event = node;
                    }
                }
            }
            assertThat(event).as("event for %s on Kafka", txId).isNotNull();
            assertThat(event.get("amountCents").asLong()).isEqualTo(1_234);
            assertThat(event.get("fromAccountId").asText()).isEqualTo(from.toString());
            assertThat(event.get("eventId").asText()).isNotBlank();
        }
    }

    @Test
    void rejectedTransferWritesNoEvent() {
        UUID from = account(100);
        UUID to = account(0);
        TransferResult result = transfers.transfer(UUID.randomUUID().toString(), new TransferRequest(from, to, 5_000L));

        assertThat(outboxRows(result.body().transactionId(), "true")).isZero();
    }

    @Test
    void retriedRequestWritesExactlyOneEvent() {
        UUID from = account(5_000);
        UUID to = account(0);
        String key = UUID.randomUUID().toString();
        TransferResult first = transfers.transfer(key, new TransferRequest(from, to, 100L));
        transfers.transfer(key, new TransferRequest(from, to, 100L));
        transfers.transfer(key, new TransferRequest(from, to, 100L));

        assertThat(outboxRows(first.body().transactionId(), "true")).isEqualTo(1);
    }

    private long outboxRows(UUID txId, String condition) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND " + condition,
                Long.class, txId);
    }

    private UUID account(long balance) {
        UUID id = accounts.create(new CreateAccountRequest("outbox", null)).id();
        if (balance > 0) accounts.deposit(UUID.randomUUID().toString(), id, balance);
        return id;
    }

    private KafkaConsumer<String, String> consumer() {
        var consumer = new KafkaConsumer<String, String>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
        consumer.subscribe(List.of(topic));
        return consumer;
    }
}
