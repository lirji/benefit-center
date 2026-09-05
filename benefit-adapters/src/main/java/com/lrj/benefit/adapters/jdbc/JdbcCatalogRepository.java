package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.BenefitCatalogRepository;
import com.lrj.benefit.contract.BenefitType;
import com.lrj.benefit.domain.model.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class JdbcCatalogRepository implements BenefitCatalogRepository {
    private final JdbcTemplate jdbc;
    public JdbcCatalogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<BenefitSku> findSku(String tenantId, String skuId) {
        List<BenefitSku> values = jdbc.query("""
                SELECT tenant_id,sku_id,benefit_type,face_value_minor,currency,status,validity_type,
                       valid_from,valid_to,relative_days,usable_weekdays,daily_quota,user_limit_per_day,
                       user_limit_total,equivalent_sku_id,version
                FROM bc_benefit_sku WHERE tenant_id=? AND sku_id=?
                """, this::mapSku, tenantId, skuId);
        return values.stream().findFirst();
    }

    @Override
    public Optional<BenefitSku> findSkuVersion(String tenantId, String skuId, long version) {
        List<BenefitSku> values = jdbc.query("""
                SELECT tenant_id,sku_id,benefit_type,face_value_minor,currency,status,validity_type,
                       valid_from,valid_to,relative_days,usable_weekdays,daily_quota,user_limit_per_day,
                       user_limit_total,equivalent_sku_id,version
                FROM bc_sku_template_version WHERE tenant_id=? AND sku_id=? AND version=?
                """, this::mapSku, tenantId, skuId, version);
        if (!values.isEmpty()) return Optional.of(values.getFirst());
        // 兼容迁移前或测试夹具直接写入的当前模板；只有世代一致时才允许回退。
        return findSku(tenantId, skuId).filter(value -> value.version() == version);
    }

    @Override public List<ChannelRoute> routes(String tenantId, String skuId) {
        return jdbc.query("""
                SELECT route_id,sku_id,priority_no,channel_code,owner_type,fallback_route_id,reserve_mode,enabled
                FROM bc_channel_route WHERE tenant_id=? AND sku_id=? ORDER BY priority_no
                """, (rs, row) -> {
            String channel = rs.getString("channel_code");
            boolean center = channel.startsWith("CENTER_");
            return new ChannelRoute(rs.getString("route_id"), rs.getString("sku_id"),
                    rs.getInt("priority_no"), channel, InventoryOwnerType.valueOf(rs.getString("owner_type")),
                    rs.getString("fallback_route_id"), InventoryReserveMode.valueOf(rs.getString("reserve_mode")),
                    rs.getBoolean("enabled"),
                    new AdapterCapabilities(true, true, true, center));
        }, tenantId, skuId);
    }

    private BenefitSku mapSku(ResultSet rs, int row) throws SQLException {
        return new BenefitSku(rs.getString("tenant_id"), rs.getString("sku_id"),
                BenefitType.valueOf(rs.getString("benefit_type")), (Long) rs.getObject("face_value_minor"),
                rs.getString("currency"), status(rs.getString("status")),
                ValidityType.valueOf(rs.getString("validity_type")),
                instant(rs.getTimestamp("valid_from")), instant(rs.getTimestamp("valid_to")),
                (Integer) rs.getObject("relative_days"), weekdays(rs.getString("usable_weekdays")),
                (Long) rs.getObject("daily_quota"), (Long) rs.getObject("user_limit_per_day"),
                (Long) rs.getObject("user_limit_total"), rs.getString("equivalent_sku_id"),
                rs.getLong("version"));
    }

    private static SkuTemplateStatus status(String value) {
        // 兼容滚动升级期间尚未被迁移的旧状态值。
        return switch (value) {
            case "ENABLED" -> SkuTemplateStatus.ACTIVE;
            case "DISABLED" -> SkuTemplateStatus.DRAFT;
            default -> SkuTemplateStatus.valueOf(value);
        };
    }

    private static List<Integer> weekdays(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(Integer::valueOf).toList();
    }

    private static java.time.Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
