package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.in.CatalogAdminUseCase;
import com.lrj.benefit.application.port.out.IdGenerator;
import com.lrj.benefit.application.port.out.ChannelAdapterRegistry;
import com.lrj.benefit.application.port.out.UnitOfWork;
import com.lrj.benefit.contract.BenefitType;
import com.lrj.benefit.domain.model.InventoryOwnerType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

public final class JdbcCatalogAdminService implements CatalogAdminUseCase {
    private final JdbcTemplate jdbc;
    private final UnitOfWork unitOfWork;
    private final IdGenerator ids;
    private final ChannelAdapterRegistry adapters;

    public JdbcCatalogAdminService(JdbcTemplate jdbc, UnitOfWork unitOfWork, IdGenerator ids,
                                   ChannelAdapterRegistry adapters) {
        this.jdbc = jdbc;
        this.unitOfWork = unitOfWork;
        this.ids = ids;
        this.adapters = adapters;
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
        validateMoney(command.benefitType(), command.faceValueMinor(), command.currency());
        unitOfWork.required(() -> {
            if (command.expectedVersion() == null) {
                jdbc.update("""
                        INSERT INTO bc_benefit_sku
                        (tenant_id,sku_id,benefit_type,currency,face_value_minor,status,metadata_json,version)
                        VALUES (?,?,?,?,?,?,?,0)
                        """, tenantId, command.skuId(), command.benefitType().name(), command.currency(),
                        command.faceValueMinor(), command.enabled() ? "ENABLED" : "DISABLED", null);
            } else if (jdbc.update("""
                    UPDATE bc_benefit_sku SET benefit_type=?,currency=?,face_value_minor=?,status=?,version=version+1
                    WHERE tenant_id=? AND sku_id=? AND version=?
                    """, command.benefitType().name(), command.currency(), command.faceValueMinor(),
                    command.enabled() ? "ENABLED" : "DISABLED", tenantId, command.skuId(),
                    command.expectedVersion()) != 1) throw new IllegalStateException("SKU version conflict");
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
        if (type != BenefitType.CASH && (amount != null || currency != null)) {
            throw new IllegalArgumentException("non-cash SKU cannot carry monetary fields");
        }
    }

    private static void require(String name, String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
