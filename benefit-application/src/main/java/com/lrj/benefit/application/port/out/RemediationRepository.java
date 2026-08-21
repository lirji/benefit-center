package com.lrj.benefit.application.port.out;

import com.lrj.benefit.domain.model.RemediationOrder;

import java.util.Optional;

public interface RemediationRepository {
    boolean insert(String tenantId, String sourceSystem, RemediationOrder order, String originalOperationNo,
                   String reason, String approvalRef, String requestHash);
    Optional<RemediationOrder> findByCommand(String tenantId, String sourceSystem, String externalCommandId);
    Optional<String> findCommandHash(String tenantId, String sourceSystem, String externalCommandId);
    Optional<RemediationOrder> find(String tenantId, String remediationNo);
    Optional<String> findOriginalOperationNo(String tenantId, String remediationNo);
    Optional<RemediationOrder> findByOperation(String tenantId, String operationNo);
    boolean updateExpectedVersion(String tenantId, RemediationOrder order, long expectedVersion);
}
