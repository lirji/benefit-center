package com.lrj.benefit.application.service;

import com.lrj.benefit.application.port.in.ExecuteFulfillmentUseCase;
import com.lrj.benefit.application.port.out.*;
import com.lrj.benefit.contract.FulfillmentEvent;
import com.lrj.benefit.contract.MessageEnvelope;
import com.lrj.benefit.contract.RemediationResult;
import com.lrj.benefit.domain.model.*;
import com.lrj.benefit.domain.service.RoutePolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class FulfillmentApplicationService implements ExecuteFulfillmentUseCase {
    private final AwardRepository awards;
    private final OperationRepository operations;
    private final BenefitCatalogRepository catalog;
    private final InventoryRepository inventory;
    private final LedgerRepository ledger;
    private final OutboxRepository outbox;
    private final RemediationRepository remediations;
    private final ChannelAdapterRegistry adapters;
    private final UnitOfWork unitOfWork;
    private final IdGenerator ids;
    private final Clock clock;
    private final Duration leaseDuration;
    private final RoutePolicy routePolicy = new RoutePolicy();

    public FulfillmentApplicationService(AwardRepository awards, OperationRepository operations,
                                         BenefitCatalogRepository catalog, InventoryRepository inventory,
                                         LedgerRepository ledger, OutboxRepository outbox,
                                         RemediationRepository remediations, ChannelAdapterRegistry adapters, UnitOfWork unitOfWork,
                                         IdGenerator ids, Clock clock, Duration leaseDuration) {
        this.awards = Objects.requireNonNull(awards);
        this.operations = Objects.requireNonNull(operations);
        this.catalog = Objects.requireNonNull(catalog);
        this.inventory = Objects.requireNonNull(inventory);
        this.ledger = Objects.requireNonNull(ledger);
        this.outbox = Objects.requireNonNull(outbox);
        this.remediations = Objects.requireNonNull(remediations);
        this.adapters = Objects.requireNonNull(adapters);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.leaseDuration = Objects.requireNonNull(leaseDuration);
    }

    @Override
    public BatchResult runBatch(String tenantId, int limit, String workerId) {
        if (limit <= 0) throw new IllegalArgumentException("limit must be positive");
        List<FulfillmentOperation> due = operations.findDue(tenantId, clock.instant(), limit);
        int claimed = 0, succeeded = 0, failed = 0, unknown = 0;
        for (FulfillmentOperation candidate : due) {
            Execution execution = claim(candidate, workerId);
            if (execution == null) continue;
            claimed++;
            ChannelAdapter.ChannelResult result;
            try {
                ChannelAdapter adapter = adapters.required(execution.route().channelCode());
                ChannelAdapter.ChannelCommand command = new ChannelAdapter.ChannelCommand(
                        execution.order().tenantId(), execution.operation().operationNo(),
                        execution.item().itemNo(), execution.item().skuId(), execution.order().recipientRef(),
                        execution.item().quantity(), execution.item().amountMinor(), execution.item().currency());
                result = invoke(adapter, execution, command);
            } catch (RuntimeException failure) {
                result = new ChannelAdapter.ChannelResult(ChannelAdapter.ChannelResult.ResultType.UNKNOWN,
                        null, "ADAPTER_EXCEPTION", failure.getClass().getSimpleName());
            }
            settle(execution, result, workerId);
            switch (result.type()) {
                case SUCCEEDED -> succeeded++;
                case UNKNOWN -> unknown++;
                case RETRYABLE_FAILURE, FINAL_FAILURE, NOT_ISSUED -> failed++;
            }
        }
        return new BatchResult(claimed, succeeded, failed, unknown);
    }

    private ChannelAdapter.ChannelResult invoke(ChannelAdapter adapter, Execution execution,
                                                 ChannelAdapter.ChannelCommand command) {
        if (execution.querying()) {
            if (!adapter.capabilities().supportsQuery()) {
                return new ChannelAdapter.ChannelResult(ChannelAdapter.ChannelResult.ResultType.UNKNOWN,
                        null, "QUERY_NOT_SUPPORTED", "manual provider investigation required");
            }
            return adapter.query(command);
        }
        if (execution.operation().type() == OperationType.REVERSE) return adapter.reverse(command);
        return adapter.issue(command);
    }

    private Execution claim(FulfillmentOperation candidate, String workerId) {
        return unitOfWork.required(() -> {
            Instant now = clock.instant();
            boolean querying = candidate.status() == OperationStatus.UNKNOWN;
            if (!operations.claimLease(candidate.tenantId(), candidate.operationNo(), workerId, now,
                    now.plus(leaseDuration), candidate.version())) return null;
            FulfillmentOperation operation = operations.find(candidate.tenantId(), candidate.operationNo())
                    .orElseThrow();
            AwardOrder order = awards.findByItemNo(candidate.tenantId(), operation.itemNo()).orElseThrow();
            AwardItem item = item(order, operation.itemNo());
            ChannelRoute route = route(order.tenantId(), item);
            long operationVersion = operation.version();
            long orderVersion = order.version();
            if (querying) {
                operation.markQuerying(workerId, now);
                if (operation.type() == OperationType.REVERSE) item.beginReversalQuery();
                else item.beginQuery();
            } else {
                operation.markDispatching(workerId, now);
                if (operation.type() != OperationType.REVERSE) item.dispatch();
            }
            order.touch();
            if (!operations.updateExpectedState(order.tenantId(), operation, operationVersion)
                    || !awards.updateExpectedVersion(order, orderVersion)) {
                throw new IllegalStateException("lease owner lost before channel call");
            }
            return new Execution(operation, order, item, route, querying);
        });
    }

    private void settle(Execution claimed, ChannelAdapter.ChannelResult result, String workerId) {
        unitOfWork.required(() -> {
            Instant now = clock.instant();
            FulfillmentOperation operation = operations.find(claimed.order().tenantId(),
                    claimed.operation().operationNo()).orElseThrow();
            AwardOrder order = awards.findByItemNo(claimed.order().tenantId(), operation.itemNo()).orElseThrow();
            AwardItem item = item(order, operation.itemNo());
            ChannelRoute route = route(order.tenantId(), item);
            long operationVersion = operation.version();
            long orderVersion = order.version();

            switch (result.type()) {
                case SUCCEEDED -> settleSuccess(operation, order, item, route, result, workerId, now);
                case RETRYABLE_FAILURE -> settleRetryable(operation, item, workerId, now);
                case FINAL_FAILURE -> settleFinalFailure(operation, item, workerId, now, result.errorCode());
                case UNKNOWN -> settleUnknown(operation, item, workerId, now);
                case NOT_ISSUED -> settleNotIssued(operation, order, item, route, result, workerId, now);
            }
            order.touch();
            order.recompute();
            if (!operations.updateExpectedState(order.tenantId(), operation, operationVersion)
                    || !awards.updateExpectedVersion(order, orderVersion)) {
                throw new IllegalStateException("lease owner lost while settling channel result");
            }
            operations.recordAttempt(order.tenantId(), operation.operationNo(), route.channelCode(), route.routeId(),
                    operation.operationNo(), result.providerReference(), result.type().name(), result.errorCode());
            settleRemediation(order.tenantId(), operation, result, now);
            publishFact(order, item, operation, route, result, now);
        });
    }

    private void settleSuccess(FulfillmentOperation operation, AwardOrder order, AwardItem item, ChannelRoute route,
                               ChannelAdapter.ChannelResult result, String workerId, Instant now) {
        operation.succeed(workerId, now);
        if (operation.type() == OperationType.REVERSE) {
            item.reverseSucceeded();
            inventory.returnIssued(order.tenantId(), item.skuId(), item.quantity(), owners(route),
                    item.itemNo(), operation.operationNo());
            appendLedger(order, item, operation, route, result, -item.quantity(), "REVERSAL", now);
        } else {
            item.succeed();
            inventory.commitReservations(order.tenantId(), operation.operationNo(), owners(route));
            if (route.ownerType() != InventoryOwnerType.CENTER_STOCK
                    && route.reserveMode() == InventoryReserveMode.EAGER) {
                inventory.releaseReservations(order.tenantId(), operation.operationNo(),
                        Set.of(InventoryOwnerType.CENTER_STOCK));
            }
            appendLedger(order, item, operation, route, result, item.quantity(), "ISSUE", now);
        }
    }

    private void settleRetryable(FulfillmentOperation operation, AwardItem item, String workerId, Instant now) {
        operation.failRetryable(workerId, now);
        if (operation.type() != OperationType.REVERSE) item.retryLater();
    }

    private void settleFinalFailure(FulfillmentOperation operation, AwardItem item, String workerId,
                                    Instant now, String errorCode) {
        operation.failFinal(workerId, now);
        if (operation.type() == OperationType.REVERSE) item.reversalFailed();
        else {
            item.failFinal(errorCode);
            inventory.releaseReservations(operation.tenantId(), operation.operationNo(), allOwners());
        }
    }

    private void settleUnknown(FulfillmentOperation operation, AwardItem item, String workerId, Instant now) {
        operation.markUnknown(workerId, now);
        if (operation.type() == OperationType.REVERSE) item.reversalUnknown();
        else item.markUnknown();
    }

    private void settleNotIssued(FulfillmentOperation operation, AwardOrder order, AwardItem item,
                                 ChannelRoute route, ChannelAdapter.ChannelResult result,
                                 String workerId, Instant now) {
        operation.failFinal(workerId, now);
        if (operation.type() == OperationType.REVERSE) {
            item.reversalFailed();
            return;
        }
        inventory.releaseReservations(order.tenantId(), operation.operationNo(), allOwners());
        try {
            ChannelRoute fallback = routePolicy.selectFallback(route, catalog.routes(order.tenantId(), item.skuId()), true);
            String fallbackOperationNo = ids.next("OP");
            boolean quota = inventory.reserveAvailable(order.tenantId(), item.skuId(), InventoryOwnerType.CENTER_QUOTA,
                    item.quantity(), item.itemNo(), fallbackOperationNo);
            boolean stock = fallback.ownerType() != InventoryOwnerType.CENTER_STOCK
                    || inventory.reserveAvailable(order.tenantId(), item.skuId(), InventoryOwnerType.CENTER_STOCK,
                    item.quantity(), item.itemNo(), fallbackOperationNo);
            if (!quota || !stock) {
                inventory.releaseReservations(order.tenantId(), fallbackOperationNo, allOwners());
                item.failFinal("FALLBACK_INVENTORY_EXHAUSTED");
                return;
            }
            item.fallbackAfterNotIssued(fallback.routeId());
            operations.insert(new FulfillmentOperation(order.tenantId(), fallbackOperationNo, item.itemNo(),
                    OperationType.ISSUE, "fallback:" + order.tenantId() + ':' + item.itemNo() + ':' + fallback.routeId(),
                    OperationStatus.CREATED, null, null, 0));
        } catch (IllegalStateException noFallback) {
            item.failFinal(result.errorCode());
        }
    }

    private void appendLedger(AwardOrder order, AwardItem item, FulfillmentOperation operation,
                              ChannelRoute route, ChannelAdapter.ChannelResult result,
                              long signedQuantity, String entryType, Instant now) {
        Long amount = item.amountMinor() == null ? null
                : Math.multiplyExact(item.amountMinor(), signedQuantity < 0 ? -1 : 1);
        ledger.appendIfAbsent(new LedgerRepository.LedgerEntry(order.tenantId(), ids.next("AL"), order.orderNo(),
                item.itemNo(), operation.operationNo(), entryType, amount, signedQuantity, item.currency(),
                route.ownerType().name(), route.channelCode(), result.providerReference(), now));
    }

    private void publishFact(AwardOrder order, AwardItem item, FulfillmentOperation operation, ChannelRoute route,
                             ChannelAdapter.ChannelResult result, Instant now) {
        String entryType = operation.type() == OperationType.REVERSE ? "REVERSAL" : "ISSUE";
        FulfillmentEvent internal = new FulfillmentEvent(order.orderNo(), item.itemNo(), operation.operationNo(),
                item.status().name(), route.channelCode(), result.providerReference(), result.errorCode(), now,
                "INTERNAL", item.skuId(), item.benefitType(), item.quantity(), item.amountMinor(), item.currency(), entryType);
        outbox.enqueue(new MessageEnvelope<>(ids.next("EV"), "FULFILLMENT_INTERNAL", "1.0", order.tenantId(),
                now, null, item.itemNo(), internal));
        FulfillmentEvent provider = new FulfillmentEvent(order.orderNo(), item.itemNo(), operation.operationNo(),
                result.type().name(), route.channelCode(), result.providerReference(), result.errorCode(), now,
                "PROVIDER", item.skuId(), item.benefitType(), item.quantity(), item.amountMinor(), item.currency(), entryType);
        outbox.enqueue(new MessageEnvelope<>(ids.next("EV"), "FULFILLMENT_PROVIDER", "1.0", order.tenantId(),
                now, null, item.itemNo(), provider));
    }

    private void settleRemediation(String tenantId, FulfillmentOperation operation,
                                   ChannelAdapter.ChannelResult result, Instant now) {
        if (operation.remediationNo() == null) return;
        RemediationOrder remediation = remediations.find(tenantId, operation.remediationNo()).orElseThrow();
        long version = remediation.version();
        switch (result.type()) {
            case SUCCEEDED -> remediation.succeed();
            case FINAL_FAILURE, NOT_ISSUED -> remediation.fail();
            case UNKNOWN -> {
                if (remediation.status() == RemediationStatus.DISPATCHING) remediation.unknown();
            }
            case RETRYABLE_FAILURE -> { return; }
        }
        if (!remediations.updateExpectedVersion(tenantId, remediation, version)) {
            throw new IllegalStateException("remediation state changed while settling");
        }
        var event = new RemediationResult(remediation.externalCommandId(), remediation.remediationNo(),
                remediation.status().name(), operation.operationNo(), result.errorCode());
        outbox.enqueue(new MessageEnvelope<>(ids.next("EV"), "REMEDIATION_RESULT", "1.0", tenantId,
                now, null, remediation.externalCommandId(), event));
    }

    private ChannelRoute route(String tenantId, AwardItem item) {
        return catalog.routes(tenantId, item.skuId()).stream()
                .filter(candidate -> candidate.routeId().equals(item.routeId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("selected route is unavailable: " + item.routeId()));
    }

    private static AwardItem item(AwardOrder order, String itemNo) {
        return order.items().stream().filter(value -> value.itemNo().equals(itemNo)).findFirst().orElseThrow();
    }

    private static Set<InventoryOwnerType> owners(ChannelRoute route) {
        return route.ownerType() == InventoryOwnerType.CENTER_STOCK
                ? Set.of(InventoryOwnerType.CENTER_QUOTA, InventoryOwnerType.CENTER_STOCK)
                : Set.of(InventoryOwnerType.CENTER_QUOTA);
    }

    private static Set<InventoryOwnerType> allOwners() {
        return Set.of(InventoryOwnerType.CENTER_QUOTA, InventoryOwnerType.CENTER_STOCK);
    }

    private record Execution(FulfillmentOperation operation, AwardOrder order, AwardItem item,
                             ChannelRoute route, boolean querying) {}
}
