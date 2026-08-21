package com.lrj.benefit.application.service;

import com.lrj.benefit.application.port.in.ExecuteRemediationUseCase;
import com.lrj.benefit.application.port.out.*;
import com.lrj.benefit.contract.*;
import com.lrj.benefit.domain.model.*;
import com.lrj.benefit.domain.service.RoutePolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;

public final class RemediationApplicationService implements ExecuteRemediationUseCase {
    private final RemediationRepository remediations;
    private final AwardRepository awards;
    private final OperationRepository operations;
    private final BenefitCatalogRepository catalog;
    private final InventoryRepository inventory;
    private final OutboxRepository outbox;
    private final UnitOfWork unitOfWork;
    private final IdGenerator ids;
    private final Clock clock;
    private final RoutePolicy routePolicy = new RoutePolicy();

    public RemediationApplicationService(RemediationRepository remediations, AwardRepository awards,
                                         OperationRepository operations, BenefitCatalogRepository catalog,
                                         InventoryRepository inventory, OutboxRepository outbox,
                                         UnitOfWork unitOfWork, IdGenerator ids, Clock clock) {
        this.remediations = Objects.requireNonNull(remediations);
        this.awards = Objects.requireNonNull(awards);
        this.operations = Objects.requireNonNull(operations);
        this.catalog = Objects.requireNonNull(catalog);
        this.inventory = Objects.requireNonNull(inventory);
        this.outbox = Objects.requireNonNull(outbox);
        this.unitOfWork = Objects.requireNonNull(unitOfWork);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public RemediationResult accept(String tenantId, RemediationCommand command) {
        Objects.requireNonNull(command, "command");
        requireText("tenantId", tenantId);
        requireText("externalCommandId", command.externalCommandId());
        requireText("awardItemNo", command.awardItemNo());
        requireText("reason", command.reason());
        Objects.requireNonNull(command.action(), "action");
        String requestHash = hash(command);
        return unitOfWork.required(() -> {
            var replay = remediations.findByCommand(tenantId, "recon", command.externalCommandId());
            if (replay.isPresent()) return replay(tenantId, command.externalCommandId(), requestHash, replay.get());

            AwardOrder order = awards.findByItemNo(tenantId, command.awardItemNo())
                    .orElseThrow(() -> notAllowed("award item does not exist"));
            AwardItem item = item(order, command.awardItemNo());
            validateSafetyGate(tenantId, item, command);
            var remediation = new RemediationOrder(ids.next("RM"), command.externalCommandId(),
                    command.awardItemNo(), command.action(), RemediationStatus.PROPOSED, 0);
            if (!remediations.insert(tenantId, "recon", remediation, command.originalOperationNo(),
                    command.reason(), command.approvalRef(), requestHash)) {
                return replay(tenantId, command.externalCommandId(), requestHash,
                        remediations.findByCommand(tenantId, "recon", command.externalCommandId()).orElseThrow());
            }
            if (command.action() != RemediationAction.MANUAL_REVIEW
                    && command.approvalRef() != null && !command.approvalRef().isBlank()) {
                long version = remediation.version();
                remediation.approve();
                if (!remediations.updateExpectedVersion(tenantId, remediation, version)) {
                    throw new IllegalStateException("remediation approval CAS failed");
                }
            }
            return result(remediation, null, null);
        });
    }

    @Override
    public RemediationResult execute(String tenantId, String remediationNo, String workerId) {
        return unitOfWork.required(() -> {
            RemediationOrder remediation = remediations.find(tenantId, remediationNo).orElseThrow();
            if (remediation.status() == RemediationStatus.DISPATCHING
                    || remediation.status() == RemediationStatus.SUCCEEDED
                    || remediation.status() == RemediationStatus.FAILED
                    || remediation.status() == RemediationStatus.UNKNOWN) {
                return result(remediation, null, null);
            }
            if (remediation.status() != RemediationStatus.APPROVED) {
                throw notAllowed("remediation is not approved");
            }
            AwardOrder order = awards.findByItemNo(tenantId, remediation.itemNo()).orElseThrow();
            AwardItem item = item(order, remediation.itemNo());
            FulfillmentOperation original = remediations.findOriginalOperationNo(tenantId, remediationNo)
                    .flatMap(operationNo -> operations.find(tenantId, operationNo))
                    .orElseThrow(() -> notAllowed("approved remediation lost its original operation"));
            validateExecutionSafety(item, remediation.action(), original);
            ChannelRoute route = remediation.action() == RemediationAction.REVERSE
                    ? exactIssuedRoute(tenantId, item) : currentOrPrimaryRoute(tenantId, item);
            String operationNo = ids.next("OP");
            long orderVersion = order.version();
            long remediationVersion = remediation.version();

            if (remediation.action() == RemediationAction.REISSUE) {
                reserveForReissue(tenantId, item, route, operationNo);
                order.beginRemediation();
                item.beginReissue(route.routeId());
                operations.insert(new FulfillmentOperation(tenantId, operationNo, item.itemNo(), OperationType.REISSUE,
                        "remediation:" + tenantId + ':' + remediation.externalCommandId(), remediation.remediationNo(),
                        OperationStatus.CREATED, null, null, 0));
            } else if (remediation.action() == RemediationAction.REVERSE) {
                if (!route.capabilities().supportsReverse()) throw notAllowed("selected channel cannot reverse");
                order.beginReversal();
                item.beginReverse();
                operations.insert(new FulfillmentOperation(tenantId, operationNo, item.itemNo(), OperationType.REVERSE,
                        "remediation:" + tenantId + ':' + remediation.externalCommandId(), remediation.remediationNo(),
                        OperationStatus.CREATED, null, null, 0));
            } else {
                throw notAllowed("manual review cannot be executed automatically");
            }
            remediation.dispatch();
            order.touch();
            if (!awards.updateExpectedVersion(order, orderVersion)
                    || !remediations.updateExpectedVersion(tenantId, remediation, remediationVersion)) {
                throw new IllegalStateException("remediation dispatch CAS failed");
            }
            var event = new RemediationResult(remediation.externalCommandId(), remediation.remediationNo(),
                    remediation.status().name(), operationNo, null);
            outbox.enqueue(new MessageEnvelope<>(ids.next("EV"), "REMEDIATION_DISPATCHED", "1.0", tenantId,
                    clock.instant(), null, item.itemNo(), event));
            return event;
        });
    }

    @Override
    public RemediationResult get(String tenantId, String remediationNo) {
        requireText("tenantId", tenantId);
        requireText("remediationNo", remediationNo);
        return remediations.find(tenantId, remediationNo).map(value -> result(value, null, null))
                .orElseThrow(() -> notAllowed("remediation does not exist"));
    }

    private void validateSafetyGate(String tenantId, AwardItem item, RemediationCommand command) {
        if (command.action() == RemediationAction.MANUAL_REVIEW) return;
        requireText("originalOperationNo", command.originalOperationNo());
        FulfillmentOperation original = operations.find(tenantId, command.originalOperationNo())
                .orElseThrow(() -> notAllowed("original operation does not exist"));
        if (!original.itemNo().equals(item.itemNo())) throw notAllowed("operation does not belong to item");
        if (original.status() == OperationStatus.UNKNOWN || original.status() == OperationStatus.QUERYING) {
            throw notAllowed("UNKNOWN must be queried before remediation");
        }
        if (command.action() == RemediationAction.REISSUE
                && (item.status() != AwardItemStatus.FAILED_FINAL || original.status() != OperationStatus.FAILED_FINAL)) {
            throw notAllowed("reissue requires an explicitly not-issued final failure");
        }
        if (command.action() == RemediationAction.REVERSE
                && (item.status() != AwardItemStatus.SUCCEEDED || original.status() != OperationStatus.SUCCEEDED)) {
            throw notAllowed("reverse requires a succeeded item and operation");
        }
    }

    private static void validateExecutionSafety(AwardItem item, RemediationAction action,
                                                FulfillmentOperation original) {
        if (!original.itemNo().equals(item.itemNo())) throw notAllowed("operation does not belong to item");
        if (original.status() == OperationStatus.UNKNOWN || original.status() == OperationStatus.QUERYING) {
            throw notAllowed("UNKNOWN must be queried before remediation");
        }
        if (action == RemediationAction.REISSUE
                && (item.status() != AwardItemStatus.FAILED_FINAL
                || original.status() != OperationStatus.FAILED_FINAL)) {
            throw notAllowed("reissue safety state changed after approval");
        }
        if (action == RemediationAction.REVERSE
                && (item.status() != AwardItemStatus.SUCCEEDED
                || original.status() != OperationStatus.SUCCEEDED)) {
            throw notAllowed("reverse safety state changed after approval");
        }
    }

    private void reserveForReissue(String tenantId, AwardItem item, ChannelRoute route, String operationNo) {
        boolean quota = inventory.reserveAvailable(tenantId, item.skuId(), InventoryOwnerType.CENTER_QUOTA,
                item.quantity(), item.itemNo(), operationNo);
        boolean needsStock = route.ownerType() == InventoryOwnerType.CENTER_STOCK
                || (route.reserveMode() == InventoryReserveMode.EAGER
                && catalog.routes(tenantId, item.skuId()).stream().anyMatch(candidate -> candidate.enabled()
                && candidate.routeId().equals(route.fallbackRouteId())
                && candidate.ownerType() == InventoryOwnerType.CENTER_STOCK));
        boolean stock = !needsStock
                || inventory.reserveAvailable(tenantId, item.skuId(), InventoryOwnerType.CENTER_STOCK,
                item.quantity(), item.itemNo(), operationNo);
        if (!quota || !stock) {
            inventory.releaseReservations(tenantId, operationNo,
                    Set.of(InventoryOwnerType.CENTER_QUOTA, InventoryOwnerType.CENTER_STOCK));
            throw new BenefitApplicationException(BenefitErrorCode.INVENTORY_INSUFFICIENT,
                    "inventory is insufficient for reissue");
        }
    }

    private ChannelRoute currentOrPrimaryRoute(String tenantId, AwardItem item) {
        var routes = catalog.routes(tenantId, item.skuId());
        if (item.routeId() != null) {
            var current = routes.stream().filter(route -> route.routeId().equals(item.routeId()) && route.enabled())
                    .findFirst();
            if (current.isPresent()) return current.get();
        }
        return routePolicy.selectPrimary(routes);
    }

    private ChannelRoute exactIssuedRoute(String tenantId, AwardItem item) {
        if (item.routeId() == null) throw notAllowed("issued item has no original route");
        return catalog.routes(tenantId, item.skuId()).stream()
                .filter(route -> route.routeId().equals(item.routeId()))
                .findFirst()
                .orElseThrow(() -> notAllowed("original issue route is unavailable"));
    }

    private static AwardItem item(AwardOrder order, String itemNo) {
        return order.items().stream().filter(value -> value.itemNo().equals(itemNo)).findFirst().orElseThrow();
    }

    private static BenefitApplicationException notAllowed(String message) {
        return new BenefitApplicationException(BenefitErrorCode.REMEDIATION_NOT_ALLOWED, message);
    }

    private RemediationResult replay(String tenantId, String externalCommandId, String requestHash,
                                     RemediationOrder remediation) {
        String existing = remediations.findCommandHash(tenantId, "recon", externalCommandId).orElse(null);
        if (!requestHash.equals(existing)) {
            throw new BenefitApplicationException(BenefitErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,
                    "remediation command id was reused with another payload");
        }
        return result(remediation, null, null);
    }

    private static String hash(RemediationCommand command) {
        String material = String.join("\u001f", command.externalCommandId(), command.action().name(),
                command.awardItemNo(), value(command.originalOperationNo()), value(command.reason()),
                value(command.approvalRef()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String value(String value) { return value == null ? "<null>" : value; }

    private static RemediationResult result(RemediationOrder order, String reference, String error) {
        return new RemediationResult(order.externalCommandId(), order.remediationNo(), order.status().name(), reference, error);
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
