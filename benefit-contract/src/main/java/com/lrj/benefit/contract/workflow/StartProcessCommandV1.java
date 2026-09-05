package com.lrj.benefit.contract.workflow;

import java.util.Map;

/** 发起 workflow-platform 流程实例的 v1 载荷。 */
public record StartProcessCommandV1(
        String processDefinitionKey,
        String businessKey,
        String idempotencyKey,
        String initiator,
        Map<String, Object> variables) {
}
