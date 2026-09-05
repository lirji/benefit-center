package com.lrj.benefit.adapters.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.port.in.WorkflowSkuApprovalUseCase;
import com.lrj.benefit.contract.workflow.WorkflowActionRequestedV1;
import com.lrj.benefit.contract.workflow.WorkflowTopics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 消费 SKU 上线审批动作。这里只负责严格编解码，双层幂等与状态/outbox 原子性由用例实现保证。
 */
@Component
@ConditionalOnProperty(name = "workflow.kafka.enabled", havingValue = "true")
public final class KafkaSkuGoLiveActionConsumer {
    private final ObjectMapper json;
    private final WorkflowSkuApprovalUseCase approval;
    private final byte[] workflowSigningKey;

    public KafkaSkuGoLiveActionConsumer(
            ObjectMapper json, WorkflowSkuApprovalUseCase approval,
            @org.springframework.beans.factory.annotation.Value("${workflow.kafka.source-signing-keys:}")
            String workflowSigningKeys) {
        this.json = json;
        this.approval = approval;
        this.workflowSigningKey = WorkflowKafkaSigning.keyFor(workflowSigningKeys, "workflow-server");
    }

    /** 处理 workflow.action.requested.v1；异常交给 Kafka error handler 重试并最终进入 DLQ。 */
    @KafkaListener(topics = "${workflow.kafka.requested-topic:workflow.action.requested.v1}",
            groupId = "${workflow.kafka.consumer-group:benefit-wf-sku-golive}")
    public void consume(ConsumerRecord<String, String> record) throws Exception {
        String raw = record.value();
        WorkflowKafkaSigning.verify(workflowSigningKey, raw,
                record.headers().lastHeader(WorkflowKafkaSigning.SIGNATURE_HEADER));
        JsonNode envelope = json.readTree(raw);
        if (envelope.path("contractVersion").asInt(-1) != 1) {
            throw new IllegalArgumentException("unsupported workflow contractVersion");
        }
        if (!WorkflowTopics.ACTION_REQUESTED.equals(required(envelope, "eventType"))) {
            throw new IllegalArgumentException("unsupported workflow eventType");
        }
        if (!"workflow-server".equals(required(envelope, "source"))) {
            throw new IllegalArgumentException("unsupported workflow event source");
        }
        String tenantId = required(envelope, "tenantId");
        String eventId = required(envelope, "eventId");
        JsonNode payloadNode = envelope.path("payload");
        if (!payloadNode.isObject()) throw new IllegalArgumentException("workflow payload is required");
        WorkflowActionRequestedV1 action = json.treeToValue(payloadNode, WorkflowActionRequestedV1.class);
        // 共享 topic 上还有 HIS 等其它流程；本 group 只处理 SKU 上线，不能把无关动作送进 DLQ。
        if (!"benefitSkuGoLive".equals(action.processDefinitionKey())) return;
        // actionId 是业务幂等键，哈希必须基于解码后的规范字段顺序，避免 JSON 属性换序被误判为冲突。
        String canonicalActionHash = sha256(json.writeValueAsString(action));
        approval.apply(tenantId, eventId, text(envelope, "correlationId"), sha256(raw),
                canonicalActionHash, action);
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing workflow event field: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
