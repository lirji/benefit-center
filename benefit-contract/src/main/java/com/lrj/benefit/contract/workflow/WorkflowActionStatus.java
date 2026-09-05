package com.lrj.benefit.contract.workflow;

/** workflow.action.applied.v1 支持的业务落地结果。 */
public enum WorkflowActionStatus {
    APPLIED,
    REJECTED_BY_BUSINESS,
    FAILED_RETRYABLE,
    FAILED_FINAL
}
