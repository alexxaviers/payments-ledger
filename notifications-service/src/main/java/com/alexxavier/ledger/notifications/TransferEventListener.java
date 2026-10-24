package com.alexxavier.ledger.notifications;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka listener. The offset is committed only after {@link NotificationProcessor#process} returns, i.e. after
 * the database transaction committed. A crash between the two causes a redelivery, which the processor skips.
 */
@Component
public class TransferEventListener {

    private static final Logger log = LoggerFactory.getLogger(TransferEventListener.class);

    private final NotificationProcessor processor;
    private final ObjectMapper json;
    private final Counter processedCounter;
    private final Counter duplicateCounter;

    public TransferEventListener(NotificationProcessor processor, ObjectMapper json, MeterRegistry registry) {
        this.processor = processor;
        this.json = json;
        this.processedCounter = Counter.builder("notifications.events").tag("result", "processed")
                .description("TransferCompleted events acted on").register(registry);
        this.duplicateCounter = Counter.builder("notifications.events").tag("result", "duplicate")
                .description("Redelivered events skipped by the idempotency check").register(registry);
    }

    @KafkaListener(topics = "${notifications.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onTransferCompleted(ConsumerRecord<String, String> record) throws Exception {
        TransferCompletedEvent event = json.readValue(record.value(), TransferCompletedEvent.class);
        boolean processed = processor.process(event);
        if (processed) {
            processedCounter.increment();
        } else {
            duplicateCounter.increment();
            log.info("Skipped duplicate delivery of event {} (partition {}, offset {})",
                    event.eventId(), record.partition(), record.offset());
        }
    }
}
