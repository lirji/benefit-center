package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.in.CatalogAdminUseCase;
import com.lrj.benefit.application.port.in.WorkflowSkuApprovalUseCase;
import com.lrj.benefit.application.port.out.IdGenerator;
import com.lrj.benefit.application.port.out.InboxRepository;
import com.lrj.benefit.application.port.out.ChannelAdapterRegistry;
import com.lrj.benefit.application.port.out.UnitOfWork;
import com.lrj.benefit.application.port.out.SkuTemplateCache;
import com.lrj.benefit.application.port.out.OutboxRepository;
import com.lrj.benefit.application.port.out.BenefitCatalogRepository;
import com.lrj.benefit.contract.BenefitType;
import com.lrj.benefit.contract.MessageEnvelope;
import com.lrj.benefit.contract.SkuTemplateChangedEvent;
import com.lrj.benefit.contract.BenefitErrorCode;
import com.lrj.benefit.contract.workflow.StartProcessCommandV1;
import com.lrj.benefit.contract.workflow.WorkflowActionAppliedV1;
import com.lrj.benefit.contract.workflow.WorkflowActionRequestedV1;
import com.lrj.benefit.contract.workflow.WorkflowActionStatus;
import com.lrj.benefit.contract.workflow.WorkflowEventEnvelopeV1;
import com.lrj.benefit.contract.workflow.WorkflowTopics;
import com.lrj.benefit.application.service.BenefitApplicationException;
import com.lrj.benefit.domain.model.InventoryOwnerType;
import com.lrj.benefit.domain.model.BenefitSku;
import com.lrj.benefit.domain.model.SkuTemplateStatus;
import com.lrj.benefit.domain.model.ValidityType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** 目录管理写服务；模板写入同时生成不可变世代快照并失效 L1。 */
public final class JdbcCatalogAdminService implements CatalogAdminUseCase, WorkflowSkuApprovalUseCase {
    private static final String PROCESS_DEFINITION_KEY = "benefitSkuGoLive";
    private static final String WORKFLOW_SOURCE = "benefit-center";
    private static final String EVENT_INBOX_GROUP = "benefit-wf-sku-golive-event";
    private static final String ACTION_INBOX_GROUP = "benefit-wf-sku-golive-action";
    private final JdbcTemplate jdbc;
    private final UnitOfWork unitOfWork;
    private final IdGenerator ids;
    private final ChannelAdapterRegistry adapters;
    private final SkuTemplateCache templateCache;
    private final BenefitCatalogRepository catalog;
    private final OutboxRepository outbox;
    private final InboxRepository inbox;
    private final Clock clock;

    public JdbcCatalogAdminService(JdbcTemplate jdbc, UnitOfWork unitOfWork, IdGenerator ids,
                                   ChannelAdapterRegistry adapters, SkuTemplateCache templateCache,
                                   BenefitCatalogRepository catalog, OutboxRepository outbox,
                                   InboxRepository inbox, Clock clock) {
        this.jdbc = jdbc;
        this.unitOfWork = unitOfWork;
        this.ids = ids;
        this.adapters = adapters;
        this.templateCache = templateCache;
        this.catalog = catalog;
        this.outbox = outbox;
        this.inbox = inbox;
        this.clock = clock;
    }

    @Override public void saveTenant(TenantCommand command) {
        require("tenantId", command.tenantId()); require("homeCell", command.homeCell());
        unitOfWork.required(() -> {
            if (command.expectedVersion() == null) {
                jdbc.update("""
                        INSERT INTO bc_tenant_config (tenant_id,home_cell,status,version)
                        VALUES (?,?,?,0)
                        """, command.tenantId(), command.homeCell(), command.enabled() ? "ENABLED" : "DISABLED");
            } else if (jdbc.update("""
                    UPDATE bc_tenant_config SET home_cell=?,status=?,version=version+1
                    WHERE tenant_id=? AND version=?
                    """, command.homeCell(), command.enabled() ? "ENABLED" : "DISABLED",
                    command.tenantId(), command.expectedVersion()) != 1) {
                throw new IllegalStateException("tenant config version conflict");
            }
        });
    }

    @Override public void saveSku(String tenantId, SkuCommand command) {
        require("tenantId", tenantId); require("skuId", command.skuId());
        if (command.benefitType() == null) throw new IllegalArgumentException("benefitType is required");
        if (command.equivalentSkuId() != null && command.equivalentSkuId().equals(command.skuId())) {
            throw new IllegalArgumentException("equivalentSkuId cannot reference itself");
        }
        unitOfWork.required(() -> {
            BenefitSku current = catalog.findSku(tenantId, command.skuId()).orElse(null);
            if (current != null && current.status() == SkuTemplateStatus.PENDING_APPROVAL) {
                throw skuError(BenefitErrorCode.SKU_APPROVAL_LOCKED,
                        "PENDING_APPROVAL template fields are locked until workflow is applied");
            }
            if (current != null && command.expectedVersion() == null) {
                throw skuError(BenefitErrorCode.SKU_VERSION_CONFLICT,
                        "expectedVersion is required when updating a SKU template");
            }
            if (current == null && command.expectedVersion() != null) {
                throw skuError(BenefitErrorCode.SKU_VERSION_CONFLICT,
                        "SKU template version does not exist");
            }
            TemplateValues values = resolveValues(command, current);
            validateTemplate(command.benefitType(), values, command.validityType() != null);
            SkuTemplateStatus publishedStatus;
            long newVersion;
            if (command.expectedVersion() == null) {
                publishedStatus = command.status() == null ? SkuTemplateStatus.DRAFT : command.status();
                if (publishedStatus != SkuTemplateStatus.DRAFT) {
                    throw skuError(BenefitErrorCode.SKU_ILLEGAL_TRANSITION,
                            "a new template must start in DRAFT");
                }
                validateEquivalentSku(tenantId, values.equivalentSkuId());
                jdbc.update("""
                        INSERT INTO bc_benefit_sku
                        (tenant_id,sku_id,benefit_type,currency,face_value_minor,status,metadata_json,version,
                         validity_type,valid_from,valid_to,relative_days,usable_weekdays,daily_quota,
                         user_limit_per_day,user_limit_total,equivalent_sku_id)
                        VALUES (?,?,?,?,?,?,?,0,?,?,?,?,?,?,?,?,?)
                        """, tenantId, command.skuId(), command.benefitType().name(), values.currency(),
                        values.faceValueMinor(), publishedStatus.name(), null, values.validityType().name(),
                        timestamp(values.validFrom()), timestamp(values.validTo()), values.relativeDays(),
                        weekdays(values.usableWeekdays()), values.dailyQuota(), values.userLimitPerDay(),
                        values.userLimitTotal(), values.equivalentSkuId());
                newVersion = 0;
            } else {
                if (current.version() != command.expectedVersion()) {
                    throw skuError(BenefitErrorCode.SKU_VERSION_CONFLICT, "SKU version conflict");
                }
                publishedStatus = targetStatus(current.status(), command.status());
                if (command.status() != null && !current.status().canTransitionTo(publishedStatus)) {
                    throw skuError(BenefitErrorCode.SKU_ILLEGAL_TRANSITION,
                            "illegal SKU transition " + current.status() + " -> " + publishedStatus);
                }
                if (current.status() == SkuTemplateStatus.DRAFT
                        && (publishedStatus == SkuTemplateStatus.PENDING_APPROVAL
                        || publishedStatus == SkuTemplateStatus.ACTIVE)) {
                    throw skuError(BenefitErrorCode.SKU_ILLEGAL_TRANSITION,
                            "DRAFT templates must use submit-for-approval before activation");
                }
                validateEquivalentSku(tenantId, values.equivalentSkuId());
                if (jdbc.update("""
                        UPDATE bc_benefit_sku
                        SET benefit_type=?,currency=?,face_value_minor=?,status=?,validity_type=?,valid_from=?,valid_to=?,
                            relative_days=?,usable_weekdays=?,daily_quota=?,user_limit_per_day=?,user_limit_total=?,
                            equivalent_sku_id=?,version=version+1
                        WHERE tenant_id=? AND sku_id=? AND version=?
                        """, command.benefitType().name(), values.currency(), values.faceValueMinor(),
                        publishedStatus.name(), values.validityType().name(), timestamp(values.validFrom()),
                        timestamp(values.validTo()), values.relativeDays(), weekdays(values.usableWeekdays()),
                        values.dailyQuota(), values.userLimitPerDay(), values.userLimitTotal(),
                        values.equivalentSkuId(),
                        tenantId, command.skuId(), command.expectedVersion()) != 1) {
                    throw skuError(BenefitErrorCode.SKU_VERSION_CONFLICT, "SKU version conflict");
                }
                newVersion = command.expectedVersion() + 1;
            }
            insertTemplateSnapshot(tenantId, command.skuId(), command.benefitType(), values,
                    publishedStatus, newVersion);
            enqueueSkuChanged(tenantId, command.skuId(), command.benefitType(), values,
                    publishedStatus, newVersion);
            invalidateTemplateAfterCommit(tenantId, command.skuId());
        });
    }

    /**
     * 提交首次上线审批：状态 CAS 与 workflow.command.start.v1 共用本地事务，202 只表示请求已受理。
     */
    @Override
    public SkuSubmitAcceptance submitSkuForApproval(String tenantId, String skuId, long expectedVersion,
                                                    String initiator) {
        require("tenantId", tenantId);
        require("skuId", skuId);
        require("initiator", initiator);
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion must not be negative");
        return unitOfWork.required(() -> {
            List<ApprovalState> locked = jdbc.query("""
                    SELECT status,version FROM bc_benefit_sku
                    WHERE tenant_id=? AND sku_id=? FOR UPDATE
                    """, (rs, row) -> new ApprovalState(SkuTemplateStatus.valueOf(rs.getString("status")),
                    rs.getLong("version")), tenantId, skuId);
            if (locked.isEmpty()) {
                throw skuError(BenefitErrorCode.SKU_NOT_DRAFT, "SKU template is not DRAFT");
            }
            ApprovalState state = locked.getFirst();
            if (state.status() == SkuTemplateStatus.PENDING_APPROVAL) {
                throw skuError(BenefitErrorCode.SKU_SUBMIT_IN_FLIGHT,
                        "SKU template already has an approval in flight");
            }
            if (state.status() != SkuTemplateStatus.DRAFT) {
                throw skuError(BenefitErrorCode.SKU_NOT_DRAFT, "SKU template is not DRAFT");
            }
            if (state.version() != expectedVersion) {
                throw skuError(BenefitErrorCode.SKU_VERSION_CONFLICT, "SKU version conflict");
            }
            BenefitSku current = catalog.findSku(tenantId, skuId)
                    .orElseThrow(() -> skuError(BenefitErrorCode.SKU_NOT_DRAFT, "SKU template is not DRAFT"));
            int updated = jdbc.update("""
                    UPDATE bc_benefit_sku SET status='PENDING_APPROVAL',version=version+1
                    WHERE tenant_id=? AND sku_id=? AND status='DRAFT' AND version=?
                    """, tenantId, skuId, expectedVersion);
            if (updated != 1) {
                BenefitSku winner = catalog.findSku(tenantId, skuId).orElse(current);
                BenefitErrorCode code = winner.status() == SkuTemplateStatus.PENDING_APPROVAL
                        ? BenefitErrorCode.SKU_SUBMIT_IN_FLIGHT : BenefitErrorCode.SKU_VERSION_CONFLICT;
                throw skuError(code, "SKU approval submission lost its optimistic lock");
            }

            long pendingVersion = Math.addExact(expectedVersion, 1);
            TemplateValues values = valuesOf(current);
            insertTemplateSnapshot(tenantId, skuId, current.type(), values,
                    SkuTemplateStatus.PENDING_APPROVAL, pendingVersion);
            enqueueSkuChanged(tenantId, skuId, current.type(), values,
                    SkuTemplateStatus.PENDING_APPROVAL, pendingVersion);
            enqueueWorkflowStart(tenantId, current, expectedVersion, pendingVersion, initiator);
            invalidateTemplateAfterCommit(tenantId, skuId);
            return new SkuSubmitAcceptance(skuId, SkuTemplateStatus.PENDING_APPROVAL.name(), pendingVersion);
        });
    }

    /**
     * 按 eventId 与 actionId 双层去重落地 workflow 决定；状态更新、模板事件和 action.applied 同事务提交。
     */
    @Override
    public void apply(String tenantId, String eventId, String correlationId, String payloadHash,
                      String actionPayloadHash, WorkflowActionRequestedV1 action) {
        unitOfWork.required(() -> {
            InboxRepository.ClaimResult eventClaim = inbox.claim(tenantId, EVENT_INBOX_GROUP, eventId, payloadHash);
            if (eventClaim == InboxRepository.ClaimResult.REPLAY) return;
            if (eventClaim == InboxRepository.ClaimResult.PAYLOAD_CONFLICT) {
                throw new IllegalStateException("workflow eventId was reused with another payload");
            }
            validateWorkflowAction(action);
            InboxRepository.ClaimResult actionClaim = inbox.claim(tenantId, ACTION_INBOX_GROUP,
                    action.actionId(), actionPayloadHash);
            if (actionClaim == InboxRepository.ClaimResult.PAYLOAD_CONFLICT) {
                throw new IllegalStateException("workflow actionId was reused with another payload");
            }
            if (actionClaim == InboxRepository.ClaimResult.REPLAY) {
                inbox.markProcessed(tenantId, EVENT_INBOX_GROUP, eventId);
                return;
            }

            WorkflowActionStatus appliedStatus = WorkflowActionStatus.REJECTED_BY_BUSINESS;
            Long businessVersion = null;
            String errorCode = "SKU_APPROVAL_STATE_CHANGED";
            String errorMessage = "SKU is no longer PENDING_APPROVAL";
            BenefitSku current = catalog.findSku(tenantId, action.businessKey()).orElse(null);
            if (current != null && current.status() == SkuTemplateStatus.PENDING_APPROVAL) {
                SkuTemplateStatus target = "SKU_GO_LIVE_APPROVE".equals(action.action())
                        ? SkuTemplateStatus.ACTIVE : SkuTemplateStatus.DRAFT;
                if (!current.status().canTransitionTo(target)) {
                    throw skuError(BenefitErrorCode.SKU_ILLEGAL_TRANSITION,
                            "workflow requested an illegal SKU transition");
                }
                int changed = jdbc.update("""
                        UPDATE bc_benefit_sku SET status=?,version=version+1
                        WHERE tenant_id=? AND sku_id=? AND status='PENDING_APPROVAL' AND version=?
                        """, target.name(), tenantId, current.skuId(), current.version());
                if (changed == 1) {
                    businessVersion = Math.addExact(current.version(), 1);
                    TemplateValues values = valuesOf(current);
                    insertTemplateSnapshot(tenantId, current.skuId(), current.type(), values,
                            target, businessVersion);
                    enqueueSkuChanged(tenantId, current.skuId(), current.type(), values, target, businessVersion);
                    invalidateTemplateAfterCommit(tenantId, current.skuId());
                    appliedStatus = WorkflowActionStatus.APPLIED;
                    errorCode = null;
                    errorMessage = null;
                }
            }
            enqueueWorkflowApplied(tenantId, eventId, correlationId, action, appliedStatus,
                    businessVersion, errorCode, errorMessage);
            inbox.markProcessed(tenantId, ACTION_INBOX_GROUP, action.actionId());
            inbox.markProcessed(tenantId, EVENT_INBOX_GROUP, eventId);
        });
    }

    /**
     * 延迟到提交后失效，避免并发读取在线程提交前回源旧行并把旧世代重新放入 L1。
     */
    private void invalidateTemplateAfterCommit(String tenantId, String skuId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            templateCache.invalidateCurrent(tenantId, skuId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                templateCache.invalidateCurrent(tenantId, skuId);
            }
        });
    }

    @Override public void saveRoute(String tenantId, RouteCommand command) {
        require("tenantId", tenantId); require("routeId", command.routeId()); require("skuId", command.skuId());
        require("channelCode", command.channelCode()); require("reserveMode", command.reserveMode());
        if (command.ownerType() == null) throw new IllegalArgumentException("ownerType is required");
        if (command.priority() <= 0) throw new IllegalArgumentException("route priority must be positive");
        if (!command.reserveMode().equals("LAZY") && !command.reserveMode().equals("EAGER")) {
            throw new IllegalArgumentException("reserveMode must be LAZY or EAGER");
        }
        if (command.routeId().equals(command.fallbackRouteId())) {
            throw new IllegalArgumentException("route cannot fallback to itself");
        }
        if (command.ownerType() == InventoryOwnerType.CHANNEL_SHADOW) {
            throw new IllegalArgumentException("CHANNEL_SHADOW is an observation, not a fulfillment route owner");
        }
        unitOfWork.required(() -> {
            Integer skuExists = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM bc_benefit_sku WHERE tenant_id=? AND sku_id=?
                    """, Integer.class, tenantId, command.skuId());
            if (skuExists == null || skuExists != 1) throw new IllegalArgumentException("route SKU does not exist");
            if (command.enabled()) {
                adapters.required(command.channelCode());
                if (command.fallbackRouteId() != null) {
                    Integer fallbackExists = jdbc.queryForObject("""
                            SELECT COUNT(*) FROM bc_channel_route
                            WHERE tenant_id=? AND route_id=? AND sku_id=? AND enabled=TRUE
                            """, Integer.class, tenantId, command.fallbackRouteId(), command.skuId());
                    if (fallbackExists == null || fallbackExists != 1) {
                        throw new IllegalArgumentException("enabled route requires an enabled same-SKU fallback");
                    }
                }
            }
            if (command.expectedVersion() == null) {
                jdbc.update("""
                        INSERT INTO bc_channel_route
                        (tenant_id,route_id,sku_id,priority_no,channel_code,owner_type,fallback_route_id,
                         reserve_mode,enabled,config_ref,version)
                        VALUES (?,?,?,?,?,?,?,?,?,?,0)
                        """, tenantId, command.routeId(), command.skuId(), command.priority(), command.channelCode(),
                        command.ownerType().name(), command.fallbackRouteId(), command.reserveMode(),
                        command.enabled(), command.configRef());
            } else if (jdbc.update("""
                    UPDATE bc_channel_route SET sku_id=?,priority_no=?,channel_code=?,owner_type=?,
                        fallback_route_id=?,reserve_mode=?,enabled=?,config_ref=?,version=version+1
                    WHERE tenant_id=? AND route_id=? AND version=?
                    """, command.skuId(), command.priority(), command.channelCode(), command.ownerType().name(),
                    command.fallbackRouteId(), command.reserveMode(), command.enabled(), command.configRef(),
                    tenantId, command.routeId(), command.expectedVersion()) != 1) {
                throw new IllegalStateException("route version conflict");
            }
        });
    }

    @Override public void adjustInventory(String tenantId, InventoryCommand command, String operator) {
        require("tenantId", tenantId); require("accountId", command.accountId()); require("skuId", command.skuId());
        require("ownerId", command.ownerId()); require("requestId", command.requestId()); require("operator", operator);
        if (command.ownerType() == null) throw new IllegalArgumentException("ownerType is required");
        if (command.ownerType() == InventoryOwnerType.CHANNEL_SHADOW) {
            throw new IllegalArgumentException("channel shadow is changed only by snapshot synchronization");
        }
        unitOfWork.required(() -> {
            int exists = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM bc_inventory_account WHERE tenant_id=? AND account_id=?
                    """, Integer.class, tenantId, command.accountId());
            if (exists == 0) {
                if (command.deltaAvailable() < 0) throw new IllegalArgumentException("initial inventory cannot be negative");
                jdbc.update("""
                        INSERT INTO bc_inventory_account
                        (tenant_id,account_id,sku_id,owner_type,owner_id,available,reserved,issued,version,snapshot_at)
                        VALUES (?,?,?,?,?,?,0,0,0,NULL)
                        """, tenantId, command.accountId(), command.skuId(), command.ownerType().name(),
                        command.ownerId(), command.deltaAvailable());
            } else if (jdbc.update("""
                    UPDATE bc_inventory_account SET available=available+?,version=version+1
                    WHERE tenant_id=? AND account_id=? AND available+?>=0 AND owner_type=?
                    """, command.deltaAvailable(), tenantId, command.accountId(), command.deltaAvailable(),
                    command.ownerType().name()) != 1) throw new IllegalStateException("inventory adjustment rejected");
            try {
                jdbc.update("""
                        INSERT INTO bc_inventory_ledger
                        (tenant_id,ledger_no,account_id,item_no,operation_no,entry_type,
                         delta_available,delta_reserved,delta_issued,created_at,operator_ref,admin_request_id)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        """, tenantId, ids.next("IL"), command.accountId(), null,
                        "admin:" + command.requestId(), "ADJUST", command.deltaAvailable(), 0, 0,
                        Timestamp.from(Instant.now()), operator, command.requestId());
            } catch (DuplicateKeyException replay) {
                throw new IllegalStateException("inventory requestId was already applied");
            }
        });
    }

    @Override public void importCode(String tenantId, CodeAssetCommand command, String operator) {
        require("tenantId", tenantId); require("codeAssetId", command.codeAssetId()); require("skuId", command.skuId());
        require("codeHash", command.codeHash()); require("cipherText", command.cipherText());
        require("keyVersion", command.keyVersion()); require("operator", operator);
        Timestamp expires = command.expiresAt() == null || command.expiresAt().isBlank()
                ? null : Timestamp.from(OffsetDateTime.parse(command.expiresAt()).toInstant());
        jdbc.update("""
                INSERT INTO bc_code_asset
                (tenant_id,code_asset_id,sku_id,code_hash,cipher_text,key_version,status,reserved_item_no,expires_at,version)
                VALUES (?,?,?,?,?,?,?,NULL,?,0)
                """, tenantId, command.codeAssetId(), command.skuId(), command.codeHash(), command.cipherText(),
                command.keyVersion(), "AVAILABLE", expires);
    }

    private static void validateMoney(BenefitType type, Long amount, String currency) {
        if (type == BenefitType.CASH && (amount == null || amount <= 0 || currency == null || currency.length() != 3)) {
            throw new IllegalArgumentException("cash SKU requires amount and currency");
        }
        if ((amount == null) != (currency == null)) {
            throw new IllegalArgumentException("faceValueMinor and currency must be provided together");
        }
        if (amount != null && amount <= 0) {
            throw new IllegalArgumentException("faceValueMinor must be positive");
        }
        if (currency != null && !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("currency must be a three-letter code");
        }
    }

    private void insertTemplateSnapshot(String tenantId, String skuId, BenefitType benefitType,
                                        TemplateValues values, SkuTemplateStatus status, long version) {
        jdbc.update("""
                INSERT INTO bc_sku_template_version
                (tenant_id,sku_id,version,benefit_type,currency,face_value_minor,status,validity_type,
                 valid_from,valid_to,relative_days,usable_weekdays,daily_quota,user_limit_per_day,
                 user_limit_total,equivalent_sku_id,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, tenantId, skuId, version, benefitType.name(), values.currency(),
                values.faceValueMinor(), status.name(), values.validityType().name(),
                timestamp(values.validFrom()), timestamp(values.validTo()), values.relativeDays(),
                weekdays(values.usableWeekdays()), values.dailyQuota(), values.userLimitPerDay(),
                values.userLimitTotal(), values.equivalentSkuId(), Timestamp.from(clock.instant()));
    }

    private void enqueueSkuChanged(String tenantId, String skuId, BenefitType benefitType,
                                   TemplateValues values, SkuTemplateStatus status, long version) {
        Instant occurredAt = clock.instant();
        var event = new SkuTemplateChangedEvent(skuId, version, benefitType, status.name(),
                values.faceValueMinor(), values.currency(), values.validityType().name(),
                values.validFrom(), values.validTo(), values.relativeDays(), values.usableWeekdays(),
                values.dailyQuota(), values.userLimitPerDay(), values.userLimitTotal(),
                values.equivalentSkuId());
        outbox.enqueue(new MessageEnvelope<>(ids.next("EV"), "SKU_TEMPLATE_CHANGED", "1.0", tenantId,
                occurredAt, null, skuId, event));
    }

    private void enqueueWorkflowStart(String tenantId, BenefitSku sku, long submittedVersion,
                                      long pendingVersion, String initiator) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("skuId", sku.skuId());
        variables.put("skuVersion", pendingVersion);
        variables.put("benefitType", sku.type().name());
        putNullable(variables, "faceValueMinor", sku.amountMinor());
        putNullable(variables, "currency", sku.currency());
        variables.put("validityType", sku.validityType().name());
        putNullable(variables, "validFrom", sku.validFrom());
        putNullable(variables, "validTo", sku.validTo());
        putNullable(variables, "relativeDays", sku.relativeDays());
        String workflowIdempotencyKey = tenantId + '|' + sku.skuId() + '|' + submittedVersion;
        String eventId = UUID.randomUUID().toString();
        var payload = new StartProcessCommandV1(PROCESS_DEFINITION_KEY, sku.skuId(),
                workflowIdempotencyKey, initiator, Map.copyOf(variables));
        var envelope = new WorkflowEventEnvelopeV1<>(eventId, 1, WorkflowTopics.COMMAND_START,
                clock.instant(), WORKFLOW_SOURCE, tenantId,
                "sku-go-live:" + workflowIdempotencyKey, null, payload);
        outbox.enqueueWorkflow(envelope,
                workflowPartitionKey(tenantId, PROCESS_DEFINITION_KEY, sku.skuId()));
    }

    private void enqueueWorkflowApplied(String tenantId, String causationId, String correlationId,
                                        WorkflowActionRequestedV1 action, WorkflowActionStatus status,
                                        Long businessVersion, String errorCode, String errorMessage) {
        String eventId = UUID.randomUUID().toString();
        var payload = new WorkflowActionAppliedV1(action.processInstanceId(), action.taskId(),
                action.processDefinitionKey(), action.businessKey(), action.actionId(), status,
                businessVersion, errorCode, errorMessage);
        var envelope = new WorkflowEventEnvelopeV1<>(eventId, 1, WorkflowTopics.ACTION_APPLIED,
                clock.instant(), WORKFLOW_SOURCE, tenantId,
                correlationId == null || correlationId.isBlank() ? action.actionId() : correlationId,
                causationId, payload);
        outbox.enqueueWorkflow(envelope,
                workflowPartitionKey(tenantId, action.processDefinitionKey(), action.businessKey()));
    }

    private static void validateWorkflowAction(WorkflowActionRequestedV1 action) {
        if (action == null || !PROCESS_DEFINITION_KEY.equals(action.processDefinitionKey())
                || !"skuGoLiveReview".equals(action.taskDefinitionKey())
                || action.processInstanceId() == null || action.processInstanceId().isBlank()
                || action.taskId() == null || action.taskId().isBlank()
                || action.businessKey() == null || action.businessKey().isBlank()
                || action.actionId() == null || action.actionId().isBlank()
                || action.actor() == null || action.actor().subjectId() == null
                || action.actor().subjectId().isBlank()
                || action.actor().username() == null || action.actor().username().isBlank()
                || (!("SKU_GO_LIVE_APPROVE".equals(action.action()))
                && !("SKU_GO_LIVE_REJECT".equals(action.action())))) {
            throw new IllegalArgumentException("unsupported SKU go-live workflow action");
        }
    }

    private static TemplateValues valuesOf(BenefitSku sku) {
        return new TemplateValues(sku.amountMinor(), sku.currency(), sku.validityType(), sku.validFrom(),
                sku.validTo(), sku.relativeDays(), sku.usableWeekdays(), sku.dailyQuota(),
                sku.userLimitPerDay(), sku.userLimitTotal(), sku.equivalentSkuId());
    }

    private static void putNullable(Map<String, Object> target, String name, Object value) {
        if (value != null) target.put(name, value);
    }

    private static String workflowPartitionKey(String tenantId, String definitionKey, String businessKey) {
        return tenantId + '|' + definitionKey + '|' + businessKey;
    }

    /** 旧客户端省略新增字段时继承当前世代，避免一次 enabled 更新意外清空有效期与限额。 */
    private static TemplateValues resolveValues(SkuCommand command, BenefitSku current) {
        ValidityType validity = command.validityType() != null ? command.validityType()
                : current == null ? ValidityType.RELATIVE : current.validityType();
        boolean changingValidityType = command.validityType() != null && current != null
                && command.validityType() != current.validityType();
        Instant validFrom = validity == ValidityType.ABSOLUTE
                ? first(command.validFrom(), changingValidityType ? null : current == null ? null : current.validFrom())
                : null;
        Instant validTo = validity == ValidityType.ABSOLUTE
                ? first(command.validTo(), changingValidityType ? null : current == null ? null : current.validTo())
                : null;
        Integer relativeDays = validity == ValidityType.RELATIVE
                ? first(command.relativeDays(), changingValidityType ? null : current == null ? null : current.relativeDays())
                : null;
        return new TemplateValues(first(command.faceValueMinor(), current == null ? null : current.amountMinor()),
                first(command.currency(), current == null ? null : current.currency()), validity, validFrom, validTo,
                relativeDays, command.usableWeekdays() != null ? List.copyOf(command.usableWeekdays())
                : current == null ? List.of() : current.usableWeekdays(),
                first(command.dailyQuota(), current == null ? null : current.dailyQuota()),
                first(command.userLimitPerDay(), current == null ? null : current.userLimitPerDay()),
                first(command.userLimitTotal(), current == null ? null : current.userLimitTotal()),
                first(command.equivalentSkuId(), current == null ? null : current.equivalentSkuId()));
    }

    private void validateEquivalentSku(String tenantId, String equivalentSkuId) {
        if (equivalentSkuId == null || equivalentSkuId.isBlank()) return;
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_benefit_sku WHERE tenant_id=? AND sku_id=?
                """, Integer.class, tenantId, equivalentSkuId);
        if (count == null || count != 1) throw new IllegalArgumentException("equivalent SKU does not exist");
    }

    private static SkuTemplateStatus targetStatus(SkuTemplateStatus current, SkuTemplateStatus requested) {
        if (requested != null) return requested;
        // enabled 是派生输出；旧客户端即使传 true 也不能绕过首次上线审批。
        return current;
    }

    private static void validateTemplate(BenefitType benefitType, TemplateValues values, boolean explicitValidity) {
        validateMoney(benefitType, values.faceValueMinor(), values.currency());
        validateValidity(values.validityType(), values.validFrom(), values.validTo(), values.relativeDays(),
                explicitValidity);
        validatePositive("dailyQuota", values.dailyQuota());
        validatePositive("userLimitPerDay", values.userLimitPerDay());
        validatePositive("userLimitTotal", values.userLimitTotal());
        validateWeekdays(values.usableWeekdays());
    }

    private static void validateValidity(ValidityType type, Instant from, Instant to,
                                         Integer relativeDays, boolean explicit) {
        if (type == ValidityType.ABSOLUTE) {
            if (from == null || to == null || !to.isAfter(from)) {
                throw new IllegalArgumentException("ABSOLUTE validity requires validFrom < validTo");
            }
            if (relativeDays != null) throw new IllegalArgumentException("ABSOLUTE validity forbids relativeDays");
        } else {
            if (from != null || to != null) throw new IllegalArgumentException("RELATIVE validity forbids absolute window");
            if (explicit && (relativeDays == null || relativeDays <= 0)) {
                throw new IllegalArgumentException("RELATIVE validity requires positive relativeDays");
            }
            if (relativeDays != null && relativeDays <= 0) {
                throw new IllegalArgumentException("relativeDays must be positive");
            }
        }
    }

    private static void validatePositive(String name, Long value) {
        if (value != null && value <= 0) throw new IllegalArgumentException(name + " must be positive");
    }

    private static void validateWeekdays(List<Integer> values) {
        if (values == null) return;
        if (values.stream().anyMatch(value -> value == null || value < 1 || value > 7)
                || values.stream().distinct().count() != values.size()) {
            throw new IllegalArgumentException("usableWeekdays must contain unique ISO weekdays 1..7");
        }
    }

    private static String weekdays(List<Integer> values) {
        if (values == null || values.isEmpty()) return null;
        return values.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static <T> T first(T requested, T fallback) {
        return requested != null ? requested : fallback;
    }

    private static void require(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static BenefitApplicationException skuError(BenefitErrorCode code, String message) {
        return new BenefitApplicationException(code, message);
    }

    private record TemplateValues(Long faceValueMinor, String currency, ValidityType validityType,
                                  Instant validFrom, Instant validTo, Integer relativeDays,
                                  List<Integer> usableWeekdays, Long dailyQuota, Long userLimitPerDay,
                                  Long userLimitTotal, String equivalentSkuId) {}
    private record ApprovalState(SkuTemplateStatus status, long version) {}
}
