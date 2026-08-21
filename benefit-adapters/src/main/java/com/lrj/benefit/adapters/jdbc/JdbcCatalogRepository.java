package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.BenefitCatalogRepository;
import com.lrj.benefit.contract.BenefitType;
import com.lrj.benefit.domain.model.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

public final class JdbcCatalogRepository implements BenefitCatalogRepository {
    private final JdbcTemplate jdbc;
    public JdbcCatalogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<BenefitSku> findSku(String tenantId, String skuId) {
        List<BenefitSku> values = jdbc.query("""
                SELECT tenant_id,sku_id,benefit_type,face_value_minor,currency,status
                FROM bc_benefit_sku WHERE tenant_id=? AND sku_id=?
                """, (rs, row) -> new BenefitSku(rs.getString("tenant_id"), rs.getString("sku_id"),
                BenefitType.valueOf(rs.getString("benefit_type")), (Long) rs.getObject("face_value_minor"),
                rs.getString("currency"), "ENABLED".equals(rs.getString("status"))), tenantId, skuId);
        return values.stream().findFirst();
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
}
