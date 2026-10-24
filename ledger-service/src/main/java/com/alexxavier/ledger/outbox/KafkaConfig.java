package com.alexxavier.ledger.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class KafkaConfig {

    @Bean
    NewTopic transfersTopic(@Value("${ledger.outbox.topic}") String topic,
                            @Value("${ledger.outbox.partitions:3}") int partitions) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(1).build();
    }
}
