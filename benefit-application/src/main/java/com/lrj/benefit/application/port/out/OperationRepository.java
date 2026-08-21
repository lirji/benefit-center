package com.lrj.benefit.application.port.out;

import com.lrj.benefit.domain.model.FulfillmentOperation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OperationRepository {
    boolean insert(FulfillmentOperation operation);
    Optional<FulfillmentOperation> find(String tenantId, String operationNo);
    List<FulfillmentOperation> findDue(String tenantId, Instant now, int limit);
    boolean claimLease(String tenantId, String operationNo, String owner, Instant now, Instant until, long expectedVersion);
    boolean updateExpectedState(String tenantId, FulfillmentOperation operation, long expectedVersion);
    void recordAttempt(String tenantId, String operationNo, String channelCode, String routeId,
                       String channelRequestNo, String providerReference, String status, String errorCode);
    int markDeadRetryable(String tenantId, Instant dueBefore, int maxAttempts);
}
