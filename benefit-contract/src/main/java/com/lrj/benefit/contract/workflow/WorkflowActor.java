package com.lrj.benefit.contract.workflow;

/** workflow 办理人的只读快照。 */
public record WorkflowActor(String subjectId, String username, String displayName) {
}
