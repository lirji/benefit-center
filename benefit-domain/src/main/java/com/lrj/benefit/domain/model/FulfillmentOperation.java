package com.lrj.benefit.domain.model;

import java.time.Instant;
import java.util.Objects;

public final class FulfillmentOperation {
    private final String tenantId;
    private final String operationNo;
    private final String itemNo;
    private final OperationType type;
    private final String idempotencyKey;
    private final String remediationNo;
    private OperationStatus status;
    private String leaseOwner;
    private Instant leaseUntil;
    private long version;

    public FulfillmentOperation(String operationNo, String itemNo, OperationType type, String idempotencyKey,
                                OperationStatus status, String leaseOwner, Instant leaseUntil, long version) {
        this("unknown", operationNo, itemNo, type, idempotencyKey, status, leaseOwner, leaseUntil, version);
    }

    public FulfillmentOperation(String tenantId, String operationNo, String itemNo, OperationType type,
                                String idempotencyKey, OperationStatus status, String leaseOwner,
                                Instant leaseUntil, long version) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.remediationNo = null;
        this.operationNo = Objects.requireNonNull(operationNo, "operationNo");
        this.itemNo = Objects.requireNonNull(itemNo, "itemNo");
        this.type = Objects.requireNonNull(type, "type");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.status = Objects.requireNonNull(status, "status");
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.version = version;
    }

    public FulfillmentOperation(String tenantId, String operationNo, String itemNo, OperationType type,
                                String idempotencyKey, String remediationNo, OperationStatus status,
                                String leaseOwner, Instant leaseUntil, long version) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.operationNo = Objects.requireNonNull(operationNo, "operationNo");
        this.itemNo = Objects.requireNonNull(itemNo, "itemNo");
        this.type = Objects.requireNonNull(type, "type");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.remediationNo = remediationNo;
        this.status = Objects.requireNonNull(status, "status");
        this.leaseOwner = leaseOwner;
        this.leaseUntil = leaseUntil;
        this.version = version;
    }

    public void lease(String owner, Instant now, Instant until) {
        Objects.requireNonNull(owner, "owner");
        if (!until.isAfter(now)) throw new IllegalArgumentException("lease must end after now");
        boolean expired = leaseUntil != null && !leaseUntil.isAfter(now);
        if (status != OperationStatus.CREATED && status != OperationStatus.FAILED_RETRYABLE
                && status != OperationStatus.UNKNOWN && !expired) {
            throw new IllegalStateException("operation is not leaseable from " + status);
        }
        status = OperationStatus.LEASED;
        leaseOwner = owner;
        leaseUntil = until;
        version++;
    }

    public void markDispatching(String owner, Instant now) {
        requireOwner(owner, now);
        status = OperationStatus.DISPATCHING;
        version++;
    }

    public void markQuerying(String owner, Instant now) {
        requireOwner(owner, now);
        if (status != OperationStatus.LEASED) {
            throw new IllegalStateException("cannot query operation from " + status);
        }
        status = OperationStatus.QUERYING;
        version++;
    }

    public void succeed(String owner, Instant now) { settle(owner, now, OperationStatus.SUCCEEDED); }
    public void failRetryable(String owner, Instant now) { settle(owner, now, OperationStatus.FAILED_RETRYABLE); }
    public void failFinal(String owner, Instant now) { settle(owner, now, OperationStatus.FAILED_FINAL); }
    public void markUnknown(String owner, Instant now) { settle(owner, now, OperationStatus.UNKNOWN); }

    private void settle(String owner, Instant now, OperationStatus target) {
        requireOwner(owner, now);
        if (status != OperationStatus.DISPATCHING && status != OperationStatus.QUERYING) {
            throw new IllegalStateException("cannot settle operation from " + status);
        }
        status = target;
        leaseOwner = null;
        leaseUntil = null;
        version++;
    }

    private void requireOwner(String owner, Instant now) {
        if (!Objects.equals(owner, leaseOwner) || leaseUntil == null || !leaseUntil.isAfter(now)) {
            throw new IllegalStateException("operation lease is not owned or has expired");
        }
    }

    public String tenantId() { return tenantId; }
    public String operationNo() { return operationNo; }
    public String itemNo() { return itemNo; }
    public OperationType type() { return type; }
    public String idempotencyKey() { return idempotencyKey; }
    public String remediationNo() { return remediationNo; }
    public OperationStatus status() { return status; }
    public String leaseOwner() { return leaseOwner; }
    public Instant leaseUntil() { return leaseUntil; }
    public long version() { return version; }
}
