package com.lrj.benefit.application.service;

import com.lrj.benefit.application.command.AwardIntentCommand;
import com.lrj.benefit.application.port.in.AcceptAwardIntentUseCase;
import com.lrj.benefit.application.port.in.QueryAwardOrderUseCase;
import com.lrj.benefit.application.port.out.*;
import com.lrj.benefit.application.result.AcceptResult;
import com.lrj.benefit.contract.*;
import com.lrj.benefit.domain.model.*;
import com.lrj.benefit.domain.service.AwardIntentValidator;
import com.lrj.benefit.domain.service.RoutePolicy;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class AwardApplicationService implements AcceptAwardIntentUseCase, QueryAwardOrderUseCase {
    private final AwardRepository awards;
    private final BenefitCatalogRepository catalog;
    private final InventoryRepository inventory;
    private final OperationRepository operations;
    private final OutboxRepository outbox;
    private final UnitOfWork unitOfWork;
    private final IdGenerator ids;
    private final Clock clock;
    private final AwardIntentValidator validator = new AwardIntentValidator();
    private final AwardIntentHasher hasher = new AwardIntentHasher();
    private final RoutePolicy routePolicy = new RoutePolicy();

    public AwardApplicationService(AwardRepository awards, BenefitCatalogRepository catalog,
                                   InventoryRepository inventory, OperationRepository operations,
                                   OutboxRepository outbox, UnitOfWork unitOfWork, IdGenerator ids, Clock clock) {
        this.awards = Objects.requireNonNull(awards);
        this.catalog = Objects.requireNonNull(catalog);
        this.inventory = Objects.requireNonNull(inventory);
        this.operations = Objects.requireNonNull(operations);
        this.outbox = Objects.requireNonNull(outbox);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public AcceptResult accept(AwardIntentCommand command) {
        Objects.requireNonNull(command, "command");
        requireText("tenantId", command.tenantId());
        requireText("homeCell", command.homeCell());
        AwardIntent intent = Objects.requireNonNull(command.intent(), "intent");
        requireText("idempotencyKey", command.idempotencyKey());
        if (!command.idempotencyKey().equals(intent.sourceRequestId())) {
            throw new BenefitApplicationException(BenefitErrorCode.INVALID_INTENT,
                    "Idempotency-Key must equal AwardIntent.sourceRequestId in v1");
        }
        validator.validate(intent);
        String requestHash = hasher.hash(intent);
        if (command.requestHash() != null && !command.requestHash().isBlank()
                && !requestHash.equals(command.requestHash())) {
            throw new BenefitApplicationException(BenefitErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,
                    "provided request hash does not match the canonical AwardIntent");
        }
        Optional<AwardOrder> existing = awards.findBySource(command.tenantId(), intent.sourceSystem(),
                intent.sourceRequestId());
        if (existing.isPresent()) return replay(existing.get(), requestHash);
        return unitOfWork.required(() -> acceptInTransaction(command, intent, requestHash));
    }

    private AcceptResult acceptInTransaction(AwardIntentCommand command, AwardIntent intent,
                                             String requestHash) {
        Optional<AwardOrder> existing = awards.findBySource(command.tenantId(), intent.sourceSystem(),
                intent.sourceRequestId());
        if (existing.isPresent()) return replay(existing.get(), requestHash);
        List<ItemPlan> plans = plan(command.tenantId(), intent);

        String orderNo = ids.next("BO");
        List<AwardItem> items = new ArrayList<>();
        for (ItemPlan plan : plans) {
            AwardItemIntent source = plan.intent();
            items.add(new AwardItem(ids.next("BI"), source.clientItemId(), source.benefitSkuId(),
                    source.benefitType(), source.quantity(), source.amountMinor(), source.currency(),
                    AwardItemStatus.PENDING, null, null, 0));
        }
        AwardOrder order = new AwardOrder(command.tenantId(), orderNo, intent.sourceSystem(),
                intent.sourceRequestId(), intent.sourceBusinessNo(), intent.recipientRef(), requestHash,
                command.homeCell(), items, AwardOrderStatus.ACCEPTED, 0);
        if (!awards.insert(order)) {
            return replay(awards.findBySource(command.tenantId(), intent.sourceSystem(), intent.sourceRequestId())
                    .orElseThrow(() -> new IllegalStateException("idempotent winner is not visible")), requestHash);
        }

        order.startProcessing();
        for (int i = 0; i < plans.size(); i++) {
            reserveAndCreateOperation(order, items.get(i), plans.get(i));
        }
        order.recompute();
        if (!awards.updateExpectedVersion(order, 0)) {
            throw new IllegalStateException("award order changed while accepting");
        }
        Instant now = clock.instant();
        for (AwardItem item : order.items()) {
            var expected = new FulfillmentEvent(order.orderNo(), item.itemNo(), null, "EXPECTED",
                    null, null, null, now, "EXPECTED", item.skuId(), item.benefitType(), item.quantity(),
                    item.amountMinor(), item.currency(), "ISSUE");
            outbox.enqueue(new MessageEnvelope<>(ids.next("EV"), "FULFILLMENT_EXPECTED", "1.0", order.tenantId(),
                    now, intent.trace().get("traceId"), item.itemNo(), expected));
        }
        return new AcceptResult(order.orderNo(), order.status(), false);
    }

    private void reserveAndCreateOperation(AwardOrder order, AwardItem item, ItemPlan plan) {
        String operationNo = ids.next("OP");
        boolean quota = inventory.reserveAvailable(order.tenantId(), item.skuId(),
                InventoryOwnerType.CENTER_QUOTA, item.quantity(), item.itemNo(), operationNo);
        if (!quota) {
            item.rejectBeforeDispatch("CENTER_QUOTA_EXHAUSTED");
            insertRejectedOperation(order, item, operationNo, "quota");
            return;
        }
        if (plan.reserveCenterStock()) {
            boolean stock = inventory.reserveAvailable(order.tenantId(), item.skuId(),
                    InventoryOwnerType.CENTER_STOCK, item.quantity(), item.itemNo(), operationNo);
            if (!stock) {
                inventory.releaseReservations(order.tenantId(), operationNo, Set.of(InventoryOwnerType.CENTER_QUOTA));
                item.rejectBeforeDispatch("CENTER_STOCK_EXHAUSTED");
                insertRejectedOperation(order, item, operationNo, "stock");
                return;
            }
        }
        item.reserve(plan.route().routeId());
        var operation = new FulfillmentOperation(order.tenantId(), operationNo, item.itemNo(), OperationType.ISSUE,
                "issue:" + order.tenantId() + ':' + item.itemNo(), OperationStatus.CREATED, null, null, 0);
        if (!operations.insert(operation)) throw new IllegalStateException("duplicate fulfillment operation");
    }

    private void insertRejectedOperation(AwardOrder order, AwardItem item, String operationNo, String reason) {
        var operation = new FulfillmentOperation(order.tenantId(), operationNo, item.itemNo(), OperationType.ISSUE,
                "pre-dispatch:" + order.tenantId() + ':' + item.itemNo() + ':' + reason,
                OperationStatus.FAILED_FINAL, null, null, 0);
        if (!operations.insert(operation)) throw new IllegalStateException("duplicate rejected operation");
    }

    private List<ItemPlan> plan(String tenantId, AwardIntent intent) {
        List<ItemPlan> plans = new ArrayList<>();
        for (AwardItemIntent item : intent.items()) {
            BenefitSku sku = catalog.findSku(tenantId, item.benefitSkuId())
                    .filter(BenefitSku::enabled)
                    .orElseThrow(() -> new BenefitApplicationException(BenefitErrorCode.SKU_NOT_FOUND,
                            "benefit SKU is missing or disabled: " + item.benefitSkuId()));
            if (sku.type() != item.benefitType()) {
                throw new BenefitApplicationException(BenefitErrorCode.INVALID_INTENT,
                        "benefit type does not match catalog: " + item.benefitSkuId());
            }
            if (sku.type() == BenefitType.CASH
                    && (!Objects.equals(sku.amountMinor(), item.amountMinor())
                    || !Objects.equals(sku.currency(), item.currency()))) {
                throw new BenefitApplicationException(BenefitErrorCode.INVALID_INTENT,
                        "cash amount/currency does not match catalog: " + item.benefitSkuId());
            }
            List<ChannelRoute> routes = catalog.routes(tenantId, item.benefitSkuId());
            ChannelRoute route = routePolicy.selectPrimary(routes);
            boolean reserveCenterStock = route.ownerType() == InventoryOwnerType.CENTER_STOCK
                    || (route.reserveMode() == InventoryReserveMode.EAGER
                    && routes.stream().anyMatch(candidate -> candidate.enabled()
                    && candidate.routeId().equals(route.fallbackRouteId())
                    && candidate.ownerType() == InventoryOwnerType.CENTER_STOCK));
            plans.add(new ItemPlan(item, route, reserveCenterStock));
        }
        return List.copyOf(plans);
    }

    private static AcceptResult replay(AwardOrder order, String requestHash) {
        if (!order.requestHash().equals(requestHash)) {
            throw new BenefitApplicationException(BenefitErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,
                    "source request id was already used with another payload");
        }
        return new AcceptResult(order.orderNo(), order.status(), true);
    }

    @Override public Optional<AwardOrder> get(String tenantId, String awardOrderNo) {
        return awards.findByOrderNo(tenantId, awardOrderNo);
    }

    @Override public Optional<AwardOrder> findBySource(String tenantId, String sourceSystem, String sourceRequestId) {
        return awards.findBySource(tenantId, sourceSystem, sourceRequestId);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private record ItemPlan(AwardItemIntent intent, ChannelRoute route, boolean reserveCenterStock) {}
}
