package com.lrj.benefit.domain.model;

import com.lrj.benefit.contract.BenefitType;

import java.util.Objects;

public final class AwardItem {
    private final String itemNo;
    private final String clientItemId;
    private final String skuId;
    private final BenefitType benefitType;
    private final long quantity;
    private final Long amountMinor;
    private final String currency;
    private AwardItemStatus status;
    private String routeId;
    private String failureCode;
    private long version;

    public AwardItem(String itemNo, String clientItemId, String skuId, BenefitType benefitType,
                     long quantity, AwardItemStatus status, long version) {
        this(itemNo, clientItemId, skuId, benefitType, quantity, null, null, status, null, null, version);
    }

    public AwardItem(String itemNo, String clientItemId, String skuId, BenefitType benefitType,
                     long quantity, Long amountMinor, String currency, AwardItemStatus status,
                     String routeId, String failureCode, long version) {
        this.itemNo = Objects.requireNonNull(itemNo, "itemNo");
        this.clientItemId = Objects.requireNonNull(clientItemId, "clientItemId");
        this.skuId = Objects.requireNonNull(skuId, "skuId");
        this.benefitType = Objects.requireNonNull(benefitType, "benefitType");
        if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
        this.quantity = quantity;
        if (benefitType == BenefitType.CASH && (amountMinor == null || amountMinor <= 0 || currency == null)) {
            throw new IllegalArgumentException("cash item requires amount and currency");
        }
        if (benefitType != BenefitType.CASH && (amountMinor != null || currency != null)) {
            throw new IllegalArgumentException("non-cash item must not carry money");
        }
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.status = Objects.requireNonNull(status, "status");
        this.routeId = routeId;
        this.failureCode = failureCode;
        this.version = version;
    }

    public void reserve() { transition(AwardItemStatus.PENDING, AwardItemStatus.RESERVED); }
    public void reserve(String selectedRouteId) {
        this.routeId = Objects.requireNonNull(selectedRouteId, "selectedRouteId");
        reserve();
    }
    public void dispatch() {
        if (status != AwardItemStatus.RESERVED && status != AwardItemStatus.REISSUING) illegal(AwardItemStatus.DISPATCHING);
        status = AwardItemStatus.DISPATCHING;
        version++;
    }
    public void succeed() {
        if (status != AwardItemStatus.DISPATCHING && status != AwardItemStatus.QUERYING
                && status != AwardItemStatus.REISSUING) illegal(AwardItemStatus.SUCCEEDED);
        status = AwardItemStatus.SUCCEEDED;
        version++;
    }
    public void failFinal() { failFinal(null); }
    public void failFinal(String code) {
        if (status != AwardItemStatus.DISPATCHING && status != AwardItemStatus.QUERYING
                && status != AwardItemStatus.REISSUING) illegal(AwardItemStatus.FAILED_FINAL);
        status = AwardItemStatus.FAILED_FINAL;
        failureCode = code;
        version++;
    }
    public void markUnknown() {
        if (status != AwardItemStatus.DISPATCHING && status != AwardItemStatus.QUERYING
                && status != AwardItemStatus.REISSUING && status != AwardItemStatus.REVERSING) illegal(AwardItemStatus.UNKNOWN);
        status = AwardItemStatus.UNKNOWN;
        version++;
    }
    public void beginQuery() {
        if (status == AwardItemStatus.UNKNOWN) {
            transition(AwardItemStatus.UNKNOWN, AwardItemStatus.QUERYING);
            return;
        }
        // A worker can die after persisting DISPATCHING/QUERYING. Operation recovery is authoritative:
        // the replacement owner must query the same operation, never issue a new one.
        if (status == AwardItemStatus.DISPATCHING || status == AwardItemStatus.QUERYING) {
            status = AwardItemStatus.QUERYING;
            version++;
            return;
        }
        illegal(AwardItemStatus.QUERYING);
    }
    public void beginReissue() { transition(AwardItemStatus.FAILED_FINAL, AwardItemStatus.REISSUING); }
    public void beginReissue(String selectedRouteId) {
        this.routeId = Objects.requireNonNull(selectedRouteId, "selectedRouteId");
        this.failureCode = null;
        beginReissue();
    }
    public void retryLater() { transition(AwardItemStatus.DISPATCHING, AwardItemStatus.RESERVED); }
    public void rejectBeforeDispatch(String code) {
        if (status != AwardItemStatus.PENDING && status != AwardItemStatus.RESERVED) {
            illegal(AwardItemStatus.FAILED_FINAL);
        }
        status = AwardItemStatus.FAILED_FINAL;
        failureCode = code;
        version++;
    }
    public void fallbackAfterNotIssued(String selectedRouteId) {
        if (status != AwardItemStatus.DISPATCHING && status != AwardItemStatus.QUERYING) {
            illegal(AwardItemStatus.RESERVED);
        }
        routeId = Objects.requireNonNull(selectedRouteId, "selectedRouteId");
        failureCode = null;
        status = AwardItemStatus.RESERVED;
        version++;
    }
    public void beginReverse() { transition(AwardItemStatus.SUCCEEDED, AwardItemStatus.REVERSING); }
    public void reverseSucceeded() { transition(AwardItemStatus.REVERSING, AwardItemStatus.REVERSED); }
    public void reversalFailed() { transition(AwardItemStatus.REVERSING, AwardItemStatus.REVERSAL_FAILED); }
    public void reversalUnknown() { transition(AwardItemStatus.REVERSING, AwardItemStatus.REVERSAL_UNKNOWN); }
    public void beginReversalQuery() {
        if (status != AwardItemStatus.REVERSAL_UNKNOWN && status != AwardItemStatus.REVERSING) {
            illegal(AwardItemStatus.REVERSING);
        }
        status = AwardItemStatus.REVERSING;
        version++;
    }

    private void transition(AwardItemStatus expected, AwardItemStatus target) {
        if (status != expected) illegal(target);
        status = target;
        version++;
    }
    private void illegal(AwardItemStatus target) {
        throw new IllegalStateException("illegal item transition " + status + " -> " + target);
    }

    public String itemNo() { return itemNo; }
    public String clientItemId() { return clientItemId; }
    public String skuId() { return skuId; }
    public BenefitType benefitType() { return benefitType; }
    public long quantity() { return quantity; }
    public Long amountMinor() { return amountMinor; }
    public String currency() { return currency; }
    public AwardItemStatus status() { return status; }
    public String routeId() { return routeId; }
    public String failureCode() { return failureCode; }
    public long version() { return version; }
}
