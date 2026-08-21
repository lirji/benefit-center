package com.lrj.benefit.application.port.out;

import java.util.Optional;

public interface PhysicalFulfillmentRepository {
    IssueResult issue(String tenantId, String itemNo, String addressRef, String operationNo);
    Optional<IssueResult> find(String tenantId, String itemNo);
    boolean reverseBeforeShipment(String tenantId, String itemNo, String operationNo);

    record IssueResult(String status, String fulfillmentReference) {}
}
