package com.lrj.benefit.contract;

import java.time.Instant;
import java.util.Objects;

public record MessageEnvelope<T>(
        String eventId,
        String eventType,
        String schemaVersion,
        String tenantId,
        Instant occurredAt,
        String traceId,
        String partitionKey,
        T payload) {

    public MessageEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(payload, "payload");
    }
}
