package com.lrj.benefit.adapters.messaging;

import com.lrj.benefit.application.port.out.EventPublisher;
import org.springframework.kafka.core.KafkaTemplate;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;

import java.util.Map;

public final class KafkaEventPublisher implements EventPublisher {
    private final KafkaTemplate<String, String> kafka;
    private final Map<String, String> topics;
    private final byte[] workflowSigningKey;

    public KafkaEventPublisher(KafkaTemplate<String, String> kafka, Map<String, String> topics,
                               String workflowSigningKeys) {
        this.kafka = kafka;
        this.topics = Map.copyOf(topics);
        this.workflowSigningKey = WorkflowKafkaSigning.keyFor(workflowSigningKeys, "benefit-center");
    }

    @Override public void publish(String eventType, String partitionKey, String payload) {
        String topic = topics.getOrDefault(eventType, topics.getOrDefault("default", "benefit.fulfillment-event.v1"));
        if (!eventType.startsWith("workflow.") || workflowSigningKey == null) {
            kafka.send(topic, partitionKey, payload).join();
            return;
        }
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, partitionKey, payload);
        record.headers().add(new RecordHeader(WorkflowKafkaSigning.SIGNATURE_HEADER,
                WorkflowKafkaSigning.sign(workflowSigningKey, payload)
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        kafka.send(record).join();
    }
}
