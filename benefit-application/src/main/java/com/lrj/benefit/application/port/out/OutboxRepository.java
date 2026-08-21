package com.lrj.benefit.application.port.out;

import com.lrj.benefit.contract.MessageEnvelope;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository {
    boolean enqueue(MessageEnvelope<?> event);
    List<OutboxMessage> claimDue(String workerId, Instant now, int limit);
    void markPublished(String tenantId, String eventId, String workerId, Instant publishedAt);
    void markFailed(String tenantId, String eventId, String workerId, Instant nextAttemptAt, int maxAttempts);

    record OutboxMessage(String tenantId, String eventId, String eventType, String aggregateId,
                         String payload, int attemptCount) {}
}
