package com.lrj.benefit.application.port.out;

import com.lrj.benefit.contract.MessageEnvelope;
import com.lrj.benefit.contract.workflow.WorkflowEventEnvelopeV1;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository {
    boolean enqueue(MessageEnvelope<?> event);
    /** 写入严格匹配 workflow-platform Published Language 的信封。 */
    boolean enqueueWorkflow(WorkflowEventEnvelopeV1<?> event, String aggregateId);
    List<OutboxMessage> claimDue(String workerId, Instant now, int limit);
    void markPublished(String tenantId, String eventId, String workerId, Instant publishedAt);
    void markFailed(String tenantId, String eventId, String workerId, Instant nextAttemptAt, int maxAttempts);

    record OutboxMessage(String tenantId, String eventId, String eventType, String aggregateId,
                         String payload, int attemptCount) {}
}
