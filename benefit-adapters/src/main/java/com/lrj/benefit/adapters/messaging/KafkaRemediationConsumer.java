package com.lrj.benefit.adapters.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.port.in.ExecuteRemediationUseCase;
import com.lrj.benefit.application.port.out.InboxRepository;
import com.lrj.benefit.contract.RemediationAction;
import com.lrj.benefit.contract.RemediationCommand;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
@ConditionalOnProperty(name = "benefit.remediation.consumer-enabled", havingValue = "true")
public class KafkaRemediationConsumer {
    private final ObjectMapper json;
    private final InboxRepository inbox;
    private final ExecuteRemediationUseCase remediation;
    private final boolean autoExecute;
    private final String consumerGroup;

    public KafkaRemediationConsumer(ObjectMapper json, InboxRepository inbox,
                                    ExecuteRemediationUseCase remediation,
                                    @Value("${benefit.remediation.auto-enabled:false}") boolean autoExecute,
                                    @Value("${benefit.remediation.consumer-group:benefit-remediation-v1}")
                                    String consumerGroup) {
        this.json = json; this.inbox = inbox; this.remediation = remediation; this.autoExecute = autoExecute;
        this.consumerGroup = consumerGroup;
    }

    @KafkaListener(topics = "${benefit.remediation.command-topic:benefit.remediation.command.v1}",
            groupId = "${benefit.remediation.consumer-group:benefit-remediation-v1}")
    public void consume(String raw) throws Exception {
        JsonNode envelope = json.readTree(raw);
        requireMajorV1(required(envelope, "schemaVersion"));
        if (!"REMEDIATION_COMMAND".equals(required(envelope, "eventType"))) {
            throw new IllegalArgumentException("unsupported remediation eventType");
        }
        String tenantId = required(envelope, "tenantId");
        String eventId = required(envelope, "eventId");
        String hash = sha256(raw);
        InboxRepository.ClaimResult claim = inbox.claim(tenantId, consumerGroup, eventId, hash);
        if (claim == InboxRepository.ClaimResult.REPLAY) return;
        if (claim == InboxRepository.ClaimResult.PAYLOAD_CONFLICT) {
            throw new IllegalStateException("remediation event payload conflict");
        }
        try {
            JsonNode value = envelope.path("payload");
            RemediationCommand command = new RemediationCommand(required(value, "externalCommandId"),
                    RemediationAction.valueOf(required(value, "action")), required(value, "awardItemNo"),
                    text(value, "originalOperationNo"), text(value, "reason"), text(value, "approvalRef"));
            var accepted = remediation.accept(tenantId, command);
            if (autoExecute && "APPROVED".equals(accepted.status())) {
                remediation.execute(tenantId, accepted.remediationNo(), "kafka-remediation");
            }
            inbox.markProcessed(tenantId, consumerGroup, eventId);
        } catch (RuntimeException failure) {
            inbox.markFailed(tenantId, consumerGroup, eventId);
            throw failure;
        }
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing event field: " + field);
        return value;
    }
    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.path(field).asText() : null;
    }
    private static void requireMajorV1(String version) {
        if (!(version.equals("1") || version.startsWith("1."))) {
            throw new IllegalArgumentException("unsupported envelope schemaVersion: " + version);
        }
    }
    private static String sha256(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
