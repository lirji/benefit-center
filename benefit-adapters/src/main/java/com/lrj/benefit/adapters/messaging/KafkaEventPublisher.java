package com.lrj.benefit.adapters.messaging;

import com.lrj.benefit.application.port.out.EventPublisher;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

public final class KafkaEventPublisher implements EventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final Map<String, String> topics;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, Map<String, String> topics) {
        this.kafka = kafka;
        this.topics = Map.copyOf(topics);
    }

    @Override public void publish(String eventType, String partitionKey, String payload) {
        String topic = topics.getOrDefault(eventType, topics.getOrDefault("default", "benefit.fulfillment-event.v1"));
        kafka.send(topic, partitionKey, payload).join();
    }
}
