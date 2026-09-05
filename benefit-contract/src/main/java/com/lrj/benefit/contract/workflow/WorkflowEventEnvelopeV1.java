package com.lrj.benefit.contract.workflow;

import java.time.Instant;

/**
 * workflow-platform 的 Published Language 信封。
 *
 * <p>本地保留一份 v1 记录，避免 benefit-center 依赖未发布的 SNAPSHOT；字段顺序和名称由集成测试钉死。</p>
 */
public record WorkflowEventEnvelopeV1<T>(
        String eventId,
        int contractVersion,
        String eventType,
        Instant occurredAt,
        String source,
        String tenantId,
        String correlationId,
        String causationId,
        T payload) {
}
