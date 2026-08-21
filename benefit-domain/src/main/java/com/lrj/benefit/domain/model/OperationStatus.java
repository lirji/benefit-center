package com.lrj.benefit.domain.model;

public enum OperationStatus {
    CREATED,
    LEASED,
    DISPATCHING,
    SUCCEEDED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    UNKNOWN,
    QUERYING
}
