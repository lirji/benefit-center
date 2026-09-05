package com.lrj.benefit.contract.workflow;

/** workflow-platform v1 主题与事件类型常量。 */
public final class WorkflowTopics {
    public static final String COMMAND_START = "workflow.command.start.v1";
    public static final String ACTION_REQUESTED = "workflow.action.requested.v1";
    public static final String ACTION_APPLIED = "workflow.action.applied.v1";

    private WorkflowTopics() {
    }
}
