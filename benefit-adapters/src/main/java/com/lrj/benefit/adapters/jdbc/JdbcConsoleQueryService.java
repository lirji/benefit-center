package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.in.ConsoleQueryUseCase;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import com.lrj.benefit.domain.model.SkuTemplateStatus;
import java.util.List;
import java.util.Optional;

/** 控制台列表与概览的 JDBC 实现；所有 SQL 都带 tenant_id=?，limit 最大 50。 */
public final class JdbcConsoleQueryService implements ConsoleQueryUseCase {
    private static final int MAX_LIMIT = 50;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcConsoleQueryService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Identity me(String tenantId, String subject, List<String> scopes) {
        return new Identity(tenantId, subject, subject, List.copyOf(scopes));
    }

    @Override
    public Overview overview(String tenantId) {
        return new Overview(
                count(tenantId, "SELECT COUNT(*) FROM bc_fulfillment_operation WHERE tenant_id=? AND status IN ('UNKNOWN','QUERYING')"),
                count(tenantId, "SELECT COUNT(*) FROM bc_award_order WHERE tenant_id=? AND status='PARTIAL_SUCCEEDED'"),
                count(tenantId, "SELECT COUNT(*) FROM bc_remediation_order WHERE tenant_id=? AND status IN ('PROPOSED','APPROVED','DISPATCHING','UNKNOWN')"),
                count(tenantId, "SELECT COUNT(*) FROM bc_benefit_sku WHERE tenant_id=?"),
                count(tenantId, "SELECT COUNT(*) FROM bc_channel_route WHERE tenant_id=? AND enabled=TRUE"),
                count(tenantId, "SELECT COUNT(*) FROM bc_benefit_sku WHERE tenant_id=? AND status='ACTIVE'"),
                countSince(tenantId, clock.instant().minus(Duration.ofHours(24))));
    }

    @Override
    public Optional<TenantView> currentTenant(String tenantId) {
        List<TenantView> rows = jdbc.query("""
                SELECT tenant_id,home_cell,status,version FROM bc_tenant_config WHERE tenant_id=?
                """, (rs, row) -> new TenantView(rs.getString("tenant_id"), rs.getString("home_cell"),
                "ENABLED".equals(rs.getString("status")), rs.getLong("version")), tenantId);
        return rows.stream().findFirst();
    }

    @Override
    public List<SkuView> listSkus(String tenantId, String status, String afterSkuId, int limit) {
        String normalizedStatus = templateStatus(status);
        String after = blankToNull(afterSkuId);
        return jdbc.query("""
                SELECT sku_id,benefit_type,face_value_minor,currency,status,validity_type,valid_from,valid_to,
                       relative_days,usable_weekdays,daily_quota,user_limit_per_day,user_limit_total,
                       equivalent_sku_id,version
                FROM bc_benefit_sku
                WHERE tenant_id=? AND (? IS NULL OR status=?) AND (? IS NULL OR sku_id>?)
                ORDER BY sku_id LIMIT ?
                """, (rs, row) -> {
            String storedStatus = normalizedStatus(rs.getString("status"));
            return new SkuView(rs.getString("sku_id"), rs.getString("benefit_type"),
                    (Long) rs.getObject("face_value_minor"), rs.getString("currency"), storedStatus,
                    "ACTIVE".equals(storedStatus), rs.getString("validity_type"),
                    timestamp(rs.getTimestamp("valid_from")), timestamp(rs.getTimestamp("valid_to")),
                    (Integer) rs.getObject("relative_days"), weekdays(rs.getString("usable_weekdays")),
                    (Long) rs.getObject("daily_quota"), (Long) rs.getObject("user_limit_per_day"),
                    (Long) rs.getObject("user_limit_total"), rs.getString("equivalent_sku_id"),
                    "PENDING_APPROVAL".equals(storedStatus) ? "benefitSkuGoLive" : null,
                    "PENDING_APPROVAL".equals(storedStatus) ? rs.getString("sku_id") : null,
                    rs.getLong("version"));
        }, tenantId, normalizedStatus, normalizedStatus, after, after, bound(limit));
    }

    @Override
    public List<RouteView> listRoutes(String tenantId, String skuId, int limit) {
        if (skuId == null || skuId.isBlank()) {
            return jdbc.query("""
                    SELECT route_id,sku_id,priority_no,channel_code,owner_type,fallback_route_id,reserve_mode,enabled,config_ref,version
                    FROM bc_channel_route WHERE tenant_id=? ORDER BY sku_id,priority_no LIMIT ?
                    """, this::mapRoute, tenantId, bound(limit));
        }
        return jdbc.query("""
                SELECT route_id,sku_id,priority_no,channel_code,owner_type,fallback_route_id,reserve_mode,enabled,config_ref,version
                FROM bc_channel_route WHERE tenant_id=? AND sku_id=? ORDER BY priority_no LIMIT ?
                """, this::mapRoute, tenantId, skuId, bound(limit));
    }

    @Override
    public List<InventoryView> listInventory(String tenantId, int limit) {
        return jdbc.query("""
                SELECT account_id,sku_id,owner_type,owner_id,available,reserved,issued,version
                FROM bc_inventory_account WHERE tenant_id=? ORDER BY sku_id,account_id LIMIT ?
                """, (rs, row) -> new InventoryView(rs.getString("account_id"), rs.getString("sku_id"),
                rs.getString("owner_type"), rs.getString("owner_id"), rs.getLong("available"),
                rs.getLong("reserved"), rs.getLong("issued"), rs.getLong("version")), tenantId, bound(limit));
    }

    @Override
    public List<AttentionOrderView> attentionOrders(String tenantId, int limit) {
        return jdbc.query("""
                SELECT o.order_no,o.source_system,o.source_request_id,o.status,o.updated_at,
                       CASE
                         WHEN o.status IN ('PARTIAL_SUCCEEDED','REMEDIATING') THEN o.status
                         ELSE 'UNKNOWN'
                       END AS reason
                FROM bc_award_order o
                WHERE o.tenant_id=?
                  AND (
                    o.status IN ('PARTIAL_SUCCEEDED','REMEDIATING')
                    OR EXISTS (
                      SELECT 1 FROM bc_award_item i
                      WHERE i.tenant_id=o.tenant_id AND i.order_no=o.order_no AND i.status IN ('UNKNOWN','QUERYING')
                    )
                    OR EXISTS (
                      SELECT 1 FROM bc_fulfillment_operation op
                      JOIN bc_award_item i ON i.tenant_id=op.tenant_id AND i.item_no=op.item_no
                      WHERE op.tenant_id=o.tenant_id AND i.order_no=o.order_no AND op.status IN ('UNKNOWN','QUERYING')
                    )
                  )
                ORDER BY o.updated_at DESC
                LIMIT ?
                """, (rs, row) -> new AttentionOrderView(rs.getString("order_no"), rs.getString("source_system"),
                rs.getString("source_request_id"), rs.getString("status"), rs.getString("reason"),
                timestamp(rs.getTimestamp("updated_at"))), tenantId, bound(limit));
    }

    @Override
    public List<RemediationView> listRemediations(String tenantId, String status, int limit) {
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                    SELECT remediation_no,action_type,item_no,status,reason,updated_at
                    FROM bc_remediation_order WHERE tenant_id=? ORDER BY updated_at DESC LIMIT ?
                    """, this::mapRemediation, tenantId, bound(limit));
        }
        return jdbc.query("""
                SELECT remediation_no,action_type,item_no,status,reason,updated_at
                FROM bc_remediation_order WHERE tenant_id=? AND status=? ORDER BY updated_at DESC LIMIT ?
                """, this::mapRemediation, tenantId, status, bound(limit));
    }

    @Override
    public List<CodeAssetView> listCodeAssets(String tenantId, String skuId, int limit) {
        if (skuId == null || skuId.isBlank()) {
            return jdbc.query("""
                    SELECT code_asset_id,sku_id,status,code_hash,expires_at
                    FROM bc_code_asset WHERE tenant_id=? ORDER BY sku_id,code_asset_id LIMIT ?
                    """, this::mapCode, tenantId, bound(limit));
        }
        return jdbc.query("""
                SELECT code_asset_id,sku_id,status,code_hash,expires_at
                FROM bc_code_asset WHERE tenant_id=? AND sku_id=? ORDER BY code_asset_id LIMIT ?
                """, this::mapCode, tenantId, skuId, bound(limit));
    }

    private RouteView mapRoute(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RouteView(rs.getString("route_id"), rs.getString("sku_id"), rs.getInt("priority_no"),
                rs.getString("channel_code"), rs.getString("owner_type"), rs.getString("fallback_route_id"),
                rs.getString("reserve_mode"), rs.getBoolean("enabled"), rs.getString("config_ref"),
                rs.getLong("version"));
    }

    private RemediationView mapRemediation(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RemediationView(rs.getString("remediation_no"), rs.getString("action_type"),
                rs.getString("item_no"), rs.getString("status"), rs.getString("reason"),
                timestamp(rs.getTimestamp("updated_at")));
    }

    private CodeAssetView mapCode(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        String hash = rs.getString("code_hash");
        String prefix = hash == null || hash.length() < 8 ? hash : hash.substring(0, 8);
        return new CodeAssetView(rs.getString("code_asset_id"), rs.getString("sku_id"),
                rs.getString("status"), prefix, timestamp(rs.getTimestamp("expires_at")));
    }

    private long count(String tenantId, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class, tenantId);
        return value == null ? 0 : value;
    }

    private long countSince(String tenantId, Instant since) {
        Long value = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_wallet_entry WHERE tenant_id=? AND created_at>=?
                """, Long.class, tenantId, Timestamp.from(since));
        return value == null ? 0 : value;
    }

    private static int bound(int limit) {
        if (limit < 1) return 20;
        return Math.min(limit, MAX_LIMIT);
    }

    private static Instant timestamp(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizedStatus(String value) {
        if ("ENABLED".equals(value)) return "ACTIVE";
        if ("DISABLED".equals(value)) return "DRAFT";
        return value;
    }

    private static String templateStatus(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : SkuTemplateStatus.valueOf(normalized).name();
    }

    private static List<Integer> weekdays(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(Integer::valueOf).toList();
    }
}
