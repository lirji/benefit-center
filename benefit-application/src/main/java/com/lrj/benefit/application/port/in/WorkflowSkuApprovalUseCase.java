package com.lrj.benefit.application.port.in;

import com.lrj.benefit.contract.workflow.WorkflowActionRequestedV1;

/** 消费 workflow 人工决定并原子落地 SKU 状态与 action.applied 回执。 */
public interface WorkflowSkuApprovalUseCase {
    void apply(String tenantId, String eventId, String correlationId,
               String payloadHash, String actionPayloadHash, WorkflowActionRequestedV1 action);
}
