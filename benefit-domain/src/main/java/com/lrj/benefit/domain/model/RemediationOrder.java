package com.lrj.benefit.domain.model;

import com.lrj.benefit.contract.RemediationAction;

import java.util.Objects;

public final class RemediationOrder {
    private final String remediationNo;
    private final String externalCommandId;
    private final String itemNo;
    private final RemediationAction action;
    private RemediationStatus status;
    private long version;

    public RemediationOrder(String remediationNo, String externalCommandId, String itemNo,
                            RemediationAction action, RemediationStatus status, long version) {
        this.remediationNo = Objects.requireNonNull(remediationNo, "remediationNo");
        this.externalCommandId = Objects.requireNonNull(externalCommandId, "externalCommandId");
        this.itemNo = Objects.requireNonNull(itemNo, "itemNo");
        this.action = Objects.requireNonNull(action, "action");
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
    }

    public void approve() { transition(RemediationStatus.PROPOSED, RemediationStatus.APPROVED); }
    public void reject() { transition(RemediationStatus.PROPOSED, RemediationStatus.REJECTED); }
    public void dispatch() { transition(RemediationStatus.APPROVED, RemediationStatus.DISPATCHING); }
    public void succeed() { settle(RemediationStatus.SUCCEEDED); }
    public void fail() { settle(RemediationStatus.FAILED); }
    public void unknown() { transition(RemediationStatus.DISPATCHING, RemediationStatus.UNKNOWN); }

    private void settle(RemediationStatus target) {
        if (status != RemediationStatus.DISPATCHING && status != RemediationStatus.UNKNOWN) {
            throw new IllegalStateException("illegal remediation transition " + status + " -> " + target);
        }
        status = target;
        version++;
    }

    private void transition(RemediationStatus expected, RemediationStatus target) {
        if (status != expected) throw new IllegalStateException("illegal remediation transition " + status + " -> " + target);
        status = target;
        version++;
    }

    public String remediationNo() { return remediationNo; }
    public String externalCommandId() { return externalCommandId; }
    public String itemNo() { return itemNo; }
    public RemediationAction action() { return action; }
    public RemediationStatus status() { return status; }
    public long version() { return version; }
}
