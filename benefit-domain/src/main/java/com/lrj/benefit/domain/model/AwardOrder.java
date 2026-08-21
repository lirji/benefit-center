package com.lrj.benefit.domain.model;

import java.util.List;
import java.util.Objects;

public final class AwardOrder {
    private final String tenantId;
    private final String orderNo;
    private final String sourceSystem;
    private final String sourceRequestId;
    private final String sourceBusinessNo;
    private final String recipientRef;
    private final String requestHash;
    private final String homeCell;
    private final List<AwardItem> items;
    private AwardOrderStatus status;
    private long version;

    public AwardOrder(String tenantId, String orderNo, String sourceSystem, String sourceRequestId,
                      String requestHash, String homeCell, List<AwardItem> items,
                      AwardOrderStatus status, long version) {
        this(tenantId, orderNo, sourceSystem, sourceRequestId, null, "unknown", requestHash,
                homeCell, items, status, version);
    }

    public AwardOrder(String tenantId, String orderNo, String sourceSystem, String sourceRequestId,
                      String sourceBusinessNo, String recipientRef, String requestHash, String homeCell,
                      List<AwardItem> items, AwardOrderStatus status, long version) {
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.orderNo = Objects.requireNonNull(orderNo, "orderNo");
        this.sourceSystem = Objects.requireNonNull(sourceSystem, "sourceSystem");
        this.sourceRequestId = Objects.requireNonNull(sourceRequestId, "sourceRequestId");
        this.sourceBusinessNo = sourceBusinessNo;
        this.recipientRef = Objects.requireNonNull(recipientRef, "recipientRef");
        this.requestHash = Objects.requireNonNull(requestHash, "requestHash");
        this.homeCell = Objects.requireNonNull(homeCell, "homeCell");
        this.items = List.copyOf(items);
        if (this.items.isEmpty()) throw new IllegalArgumentException("award order must contain items");
        this.status = Objects.requireNonNull(status, "status");
        this.version = version;
    }

    public void startProcessing() {
        if (status != AwardOrderStatus.ACCEPTED) illegal(AwardOrderStatus.PROCESSING);
        status = AwardOrderStatus.PROCESSING;
        version++;
    }

    public void recompute() {
        long succeeded = items.stream().filter(i -> i.status() == AwardItemStatus.SUCCEEDED).count();
        long failed = items.stream().filter(i -> i.status() == AwardItemStatus.FAILED_FINAL).count();
        long reversed = items.stream().filter(i -> i.status() == AwardItemStatus.REVERSED).count();
        AwardOrderStatus target;
        if (reversed == items.size()) target = AwardOrderStatus.REVERSED;
        else if (reversed > 0 && succeeded + reversed == items.size()) target = AwardOrderStatus.PARTIALLY_REVERSED;
        else if (succeeded == items.size()) target = AwardOrderStatus.SUCCEEDED;
        else if (failed == items.size()) target = AwardOrderStatus.FAILED;
        else if (succeeded + failed + reversed == items.size()) target = AwardOrderStatus.PARTIAL_SUCCEEDED;
        else target = AwardOrderStatus.PROCESSING;
        if (target != status) {
            status = target;
            version++;
        }
    }

    public void beginRemediation() {
        if (items.stream().noneMatch(i -> i.status() == AwardItemStatus.FAILED_FINAL)) {
            throw new IllegalStateException("no final-failed item to remediate");
        }
        status = AwardOrderStatus.REMEDIATING;
        version++;
    }

    public void beginReversal() {
        if (items.stream().noneMatch(i -> i.status() == AwardItemStatus.SUCCEEDED)) {
            throw new IllegalStateException("no succeeded item to reverse");
        }
        status = AwardOrderStatus.REVERSING;
        version++;
    }

    /** Advances the aggregate version when an item changes but the derived order status does not. */
    public void touch() { version++; }

    private void illegal(AwardOrderStatus target) {
        throw new IllegalStateException("illegal order transition " + status + " -> " + target);
    }

    public String tenantId() { return tenantId; }
    public String orderNo() { return orderNo; }
    public String sourceSystem() { return sourceSystem; }
    public String sourceRequestId() { return sourceRequestId; }
    public String sourceBusinessNo() { return sourceBusinessNo; }
    public String recipientRef() { return recipientRef; }
    public String requestHash() { return requestHash; }
    public String homeCell() { return homeCell; }
    public List<AwardItem> items() { return items; }
    public AwardOrderStatus status() { return status; }
    public long version() { return version; }
}
