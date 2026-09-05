package com.lrj.benefit.contract.workflow;

/** benefit-center 回告 workflow-platform 业务落地结果的 v1 载荷。 */
public record WorkflowActionAppliedV1(
        String processInstanceId,
        String taskId,
        String processDefinitionKey,
        String businessKey,
        String actionId,
        WorkflowActionStatus status,
        Long businessVersion,
        String errorCode,
        String errorMessage) {
}
