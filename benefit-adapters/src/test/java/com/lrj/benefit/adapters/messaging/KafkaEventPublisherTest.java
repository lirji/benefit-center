package com.lrj.benefit.adapters.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaEventPublisherTest {

    @Test
    @SuppressWarnings("unchecked")
    void workflowRelayAddsBenefitCenterSignatureToExactOutboxJson() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        String key = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        KafkaEventPublisher publisher = new KafkaEventPublisher(kafka,
                Map.of("workflow.command.start.v1", "workflow.command.start.v1"),
                "benefit-center=" + key);

        publisher.publish("workflow.command.start.v1", "tenant|definition|sku", "{\"eventId\":\"evt-1\"}");

        ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(sent.capture());
        assertThat(sent.getValue().topic()).isEqualTo("workflow.command.start.v1");
        assertThat(sent.getValue().key()).isEqualTo("tenant|definition|sku");
        assertThat(sent.getValue().value()).isEqualTo("{\"eventId\":\"evt-1\"}");
        assertThat(sent.getValue().headers().lastHeader(WorkflowKafkaSigning.SIGNATURE_HEADER)).isNotNull();
    }
}
