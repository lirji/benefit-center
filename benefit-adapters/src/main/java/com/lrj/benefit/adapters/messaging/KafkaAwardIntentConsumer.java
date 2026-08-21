package com.lrj.benefit.adapters.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.command.AwardIntentCommand;
import com.lrj.benefit.application.port.in.AcceptAwardIntentUseCase;
import com.lrj.benefit.application.port.out.CellRouter;
import com.lrj.benefit.application.port.out.InboxRepository;
import com.lrj.benefit.contract.AwardIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Kafka ingress equivalent of the public REST endpoint. */
@Component
@ConditionalOnProperty(name = "benefit.award-intent.consumer-enabled", havingValue = "true")
public class KafkaAwardIntentConsumer {
    private final ObjectMapper json;
    private final InboxRepository inbox;
    private final AcceptAwardIntentUseCase awards;
    private final CellRouter cells;
    private final String consumerGroup;

    public KafkaAwardIntentConsumer(ObjectMapper json, InboxRepository inbox,
                                    AcceptAwardIntentUseCase awards, CellRouter cells,
                                    @Value("${benefit.award-intent.consumer-group:benefit-award-intent-v1}")
                                    String consumerGroup) {
        this.json = json;
        this.inbox = inbox;
        this.awards = awards;
        this.cells = cells;
        this.consumerGroup = consumerGroup;
    }

    @KafkaListener(topics = "${benefit.award-intent.topic:benefit.award-intent.v1}",
            groupId = "${benefit.award-intent.consumer-group:benefit-award-intent-v1}")
    public void consume(String raw) throws Exception {
        JsonNode envelope = json.readTree(raw);
        requireMajorV1(required(envelope, "schemaVersion"));
        if (!"AWARD_INTENT".equals(required(envelope, "eventType"))) {
            throw new IllegalArgumentException("unsupported award intent eventType");
        }
        String tenantId = required(envelope, "tenantId");
        String eventId = required(envelope, "eventId");
        InboxRepository.ClaimResult claim = inbox.claim(tenantId, consumerGroup, eventId, sha256(raw));
        if (claim == InboxRepository.ClaimResult.REPLAY) return;
        if (claim == InboxRepository.ClaimResult.PAYLOAD_CONFLICT) {
            throw new IllegalStateException("award intent event payload conflict");
        }
        try {
            AwardIntent intent = json.treeToValue(envelope.path("payload"), AwardIntent.class);
            String homeCell = cells.homeCell(tenantId);
            if (!cells.isLocal(homeCell)) {
                throw new IllegalStateException("tenant is routed to another cell: " + homeCell);
            }
            awards.accept(new AwardIntentCommand(tenantId, intent.sourceRequestId(), null, homeCell, intent));
            inbox.markProcessed(tenantId, consumerGroup, eventId);
        } catch (Exception failure) {
            inbox.markFailed(tenantId, consumerGroup, eventId);
            throw failure;
        }
    }

    private static void requireMajorV1(String version) {
        if (!(version.equals("1") || version.startsWith("1."))) {
            throw new IllegalArgumentException("unsupported envelope schemaVersion: " + version);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = node.hasNonNull(field) ? node.path(field).asText() : null;
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing event field: " + field);
        return value;
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
