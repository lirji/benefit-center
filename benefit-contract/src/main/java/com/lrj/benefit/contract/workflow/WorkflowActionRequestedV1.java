package com.lrj.benefit.contract.workflow;

import java.util.Map;

/** workflow-platform 请求 benefit-center 落地人工决定的 v1 载荷。 */
public record WorkflowActionRequestedV1(
        String processInstanceId,
        String taskId,
        String taskDefinitionKey,
        String processDefinitionKey,
        String businessKey,
        String actionId,
        String action,
        WorkflowActor actor,
        Map<String, Object> parameters) {
}
