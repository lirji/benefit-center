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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Map;
import java.util.TreeMap;
import java.util.LinkedHashMap;

/** 原子受理及永久来源幂等；可选SKU版本由主库锁定快照裁决。 */
public final class AwardApplicationService implements AcceptAwardIntentUseCase, QueryAwardOrderUseCase {
    private final AwardRepository awards;
    private final BenefitCatalogRepository catalog;
    private final SkuTemplateCache templateCache;
    private final SkuAcceptanceRepository skuAcceptance;
    private final InventoryRepository inventory;
    private final UserLimitRepository userLimits;
    private final UserLimitPrecheck limitPrecheck;
    private final OperationRepository operations;
    private final OutboxRepository outbox;
    private final UnitOfWork unitOfWork;
    private final IdGenerator ids;
    private final Clock clock;
    private final AwardIntentValidator validator = new AwardIntentValidator();
    private final AwardIntentHasher hasher = new AwardIntentHasher();
    private final RoutePolicy routePolicy = new RoutePolicy();

    /** 兼容原组装调用；未提供锁端口时拒绝版本请求，不退回缓存。 */
    public AwardApplicationService(AwardRepository awards, BenefitCatalogRepository catalog,
                                   SkuTemplateCache templateCache, InventoryRepository inventory,
                                   UserLimitRepository userLimits, UserLimitPrecheck limitPrecheck,
                                   OperationRepository operations,
                                   OutboxRepository outbox, UnitOfWork unitOfWork, IdGenerator ids, Clock clock) {
        this(awards, catalog, templateCache, inventory, userLimits, limitPrecheck, operations,
                outbox, unitOfWork, ids, clock, (tenant, sku) -> {
                    throw new BenefitApplicationException(BenefitErrorCode.SKU_VERSION_CONFLICT,
                            "locked SKU acceptance is unavailable");
                });
    }

    /** 锁端口与原仓储共用受理事务，只影响实际指定版本的新请求。 */
    public AwardApplicationService(AwardRepository awards, BenefitCatalogRepository catalog,
                                   SkuTemplateCache templateCache, InventoryRepository inventory,
                                   UserLimitRepository userLimits, UserLimitPrecheck limitPrecheck,
                                   OperationRepository operations,
                                   OutboxRepository outbox, UnitOfWork unitOfWork, IdGenerator ids, Clock clock,
                                   SkuAcceptanceRepository skuAcceptance) {
        this.awards = Objects.requireNonNull(awards);
        this.catalog = Objects.requireNonNull(catalog);
        this.templateCache = Objects.requireNonNull(templateCache);
        this.skuAcceptance = Objects.requireNonNull(skuAcceptance);
        this.inventory = Objects.requireNonNull(inventory);
        this.userLimits = Objects.requireNonNull(userLimits);
        this.limitPrecheck = Objects.requireNonNull(limitPrecheck);
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
                    plan.sku().version(), source.benefitType(), source.quantity(), source.amountMinor(),
                    source.currency(), AwardItemStatus.PENDING, null, null, null, 0));
        }
        reserveUserLimits(command.tenantId(), intent.recipientRef(), items, plans);
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
            var expected = new FulfillmentEvent(order.orderNo(), item.itemNo(), item.clientItemId(),
                    order.sourceSystem(), order.sourceRequestId(), null, "EXPECTED",
                    null, null, null, now, "EXPECTED", item.skuId(), item.benefitType(), item.quantity(),
                    item.amountMinor(), item.currency(), "ISSUE");
            outbox.enqueue(new MessageEnvelope<>(ids.next("EV"), "FULFILLMENT_EXPECTED", "1.0", order.tenantId(),
                    now, intent.trace().get("traceId"), item.itemNo(), expected));
        }
        // 额度、库存及Outbox也可能等待锁；最终裁决前再检查，过期则连同已做的写入一起回滚。
        Instant completedAt = clock.instant();
        for (ItemPlan plan : plans) {
            if (plan.intent().expectedSkuVersion() != null && !plan.sku().acceptsAt(completedAt)) {
                throw new BenefitApplicationException(BenefitErrorCode.SKU_NOT_ACTIVE,
                        "locked benefit SKU validity window ended during acceptance");
            }
        }
        return new AcceptResult(order.orderNo(), order.status(), false);
    }

    private void reserveAndCreateOperation(AwardOrder order, AwardItem item, ItemPlan plan) {
        String operationNo = ids.next("OP");
        boolean quota = inventory.reserveAvailable(order.tenantId(), item.skuId(),
                InventoryOwnerType.CENTER_QUOTA, item.quantity(), item.itemNo(), operationNo);
        if (!quota) {
            releaseUserLimit(order.tenantId(), item.itemNo());
            item.rejectBeforeDispatch("CENTER_QUOTA_EXHAUSTED");
            insertRejectedOperation(order, item, operationNo, "quota");
            return;
        }
        if (plan.reserveCenterStock()) {
            boolean stock = inventory.reserveAvailable(order.tenantId(), item.skuId(),
                    InventoryOwnerType.CENTER_STOCK, item.quantity(), item.itemNo(), operationNo);
            if (!stock) {
                inventory.releaseReservations(order.tenantId(), operationNo, Set.of(InventoryOwnerType.CENTER_QUOTA));
                releaseUserLimit(order.tenantId(), item.itemNo());
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
        Map<String, BenefitSku> locked = lockExpectedSkus(tenantId, intent);
        List<ItemPlan> plans = new ArrayList<>();
        for (AwardItemIntent item : intent.items()) {
            BenefitSku sku = (locked.containsKey(item.benefitSkuId())
                    ? Optional.of(locked.get(item.benefitSkuId()))
                    : templateCache.findCurrent(tenantId, item.benefitSkuId()))
                    .orElseThrow(() -> new BenefitApplicationException(BenefitErrorCode.SKU_NOT_FOUND,
                            "benefit SKU is missing: " + item.benefitSkuId()));
            if (!sku.acceptsAt(clock.instant())) {
                throw new BenefitApplicationException(BenefitErrorCode.SKU_NOT_ACTIVE,
                        "benefit SKU is not ACTIVE in its validity window: " + item.benefitSkuId());
            }
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
            plans.add(new ItemPlan(item, sku, route, reserveCenterStock));
        }
        // 后续模板锁和路由读取可能等待，规划完成后复检最早取得的模板时间窗口。
        Instant now = clock.instant();
        for (BenefitSku sku : locked.values()) {
            if (!sku.acceptsAt(now)) throw new BenefitApplicationException(BenefitErrorCode.SKU_NOT_ACTIVE,
                    "locked benefit SKU validity window has ended");
        }
        return List.copyOf(plans);
    }

    /** 多项先按SKU排序取锁，避免不同请求按A/B反序获取锁。 */
    private Map<String, BenefitSku> lockExpectedSkus(String tenantId, AwardIntent intent) {
        Map<String, Long> expected = new TreeMap<>();
        for (AwardItemIntent item : intent.items()) {
            if (item.expectedSkuVersion() == null) continue;
            Long previous = expected.putIfAbsent(item.benefitSkuId(), item.expectedSkuVersion());
            if (previous != null && !previous.equals(item.expectedSkuVersion())) {
                throw new BenefitApplicationException(BenefitErrorCode.SKU_VERSION_CONFLICT,
                        "one SKU cannot require two versions in the same intent");
            }
        }
        Map<String, BenefitSku> result = new LinkedHashMap<>();
        expected.forEach((id, version) -> {
            BenefitSku sku = skuAcceptance.lockCurrent(tenantId, id).orElseThrow(() ->
                    new BenefitApplicationException(BenefitErrorCode.SKU_NOT_FOUND, "benefit SKU is missing"));
            if (!sku.tenantId().equals(tenantId) || !sku.skuId().equals(id) || sku.version() != version) {
                throw new BenefitApplicationException(BenefitErrorCode.SKU_VERSION_CONFLICT,
                        "benefit SKU version differs from the expected version");
            }
            result.put(id, sku);
        });
        return result;
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

    /**
     * 在库存占用前先做 L2 快速检查，再由数据库条件更新做最终裁决。
     * 任一订单项失败会抛异常并回滚同事务中已占的其它用户额度。
     */
    private void reserveUserLimits(String tenantId, String subjectRef, List<AwardItem> items,
                                   List<ItemPlan> plans) {
        LocalDate businessDate = LocalDate.now(clock);
        String dayKey = businessDate.toString();
        for (int index = 0; index < plans.size(); index++) {
            BenefitSku sku = plans.get(index).sku();
            AwardItem item = items.get(index);
            if (sku.userLimitPerDay() != null && !limitPrecheck.mayReserve(tenantId, subjectRef, sku.skuId(),
                    UserLimitRepository.PeriodType.DAY, dayKey, item.quantity(), sku.userLimitPerDay())) {
                throw limitExceeded(sku.skuId(), "DAY");
            }
            if (sku.userLimitTotal() != null && !limitPrecheck.mayReserve(tenantId, subjectRef, sku.skuId(),
                    UserLimitRepository.PeriodType.TOTAL, "ALL", item.quantity(), sku.userLimitTotal())) {
                throw limitExceeded(sku.skuId(), "TOTAL");
            }
            if (!userLimits.reserve(tenantId, subjectRef, sku.skuId(), item.itemNo(), item.quantity(),
                    sku.userLimitPerDay(), sku.userLimitTotal(), businessDate)) {
                throw limitExceeded(sku.skuId(), "DATABASE_CAS");
            }
            invalidateLimitKeys(tenantId, subjectRef, sku.skuId(), dayKey,
                    sku.userLimitPerDay(), sku.userLimitTotal());
        }
    }

    private void releaseUserLimit(String tenantId, String itemNo) {
        List<UserLimitRepository.CounterKey> keys = userLimits.counterKeysForItem(tenantId, itemNo);
        userLimits.release(tenantId, itemNo);
        for (UserLimitRepository.CounterKey key : keys) {
            limitPrecheck.invalidate(tenantId, key.subjectRef(), key.skuId(), key.periodType(), key.periodKey());
        }
    }

    private void invalidateLimitKeys(String tenantId, String subjectRef, String skuId, String dayKey,
                                     Long dailyLimit, Long totalLimit) {
        if (dailyLimit != null) {
            limitPrecheck.invalidate(tenantId, subjectRef, skuId,
                    UserLimitRepository.PeriodType.DAY, dayKey);
        }
        if (totalLimit != null) {
            limitPrecheck.invalidate(tenantId, subjectRef, skuId,
                    UserLimitRepository.PeriodType.TOTAL, "ALL");
        }
    }

    private static BenefitApplicationException limitExceeded(String skuId, String period) {
        return new BenefitApplicationException(BenefitErrorCode.USER_LIMIT_EXCEEDED,
                "user limit exceeded for SKU " + skuId + " (" + period + ")");
    }

    private record ItemPlan(AwardItemIntent intent, BenefitSku sku, ChannelRoute route,
                            boolean reserveCenterStock) {}
}
