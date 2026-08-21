package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.CodeAssetRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

public final class JdbcCodeAssetRepository implements CodeAssetRepository {
    private final JdbcTemplate jdbc;
    public JdbcCodeAssetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<IssuedCode> issueOne(String tenantId, String skuId, String itemNo, String operationNo) {
        Optional<IssuedCode> replay = findIssued(tenantId, itemNo);
        if (replay.isPresent()) return replay;
        List<String> candidates = jdbc.query("""
                SELECT code_asset_id FROM bc_code_asset
                WHERE tenant_id=? AND sku_id=? AND status='AVAILABLE'
                  AND (expires_at IS NULL OR expires_at>CURRENT_TIMESTAMP)
                ORDER BY code_asset_id LIMIT 10
                """, (rs, row) -> rs.getString(1), tenantId, skuId);
        for (String assetId : candidates) {
            try {
                if (jdbc.update("""
                        UPDATE bc_code_asset SET status='ISSUED',reserved_item_no=?,version=version+1
                        WHERE tenant_id=? AND code_asset_id=? AND status='AVAILABLE'
                        """, itemNo, tenantId, assetId) == 1) {
                    return Optional.of(new IssuedCode(assetId, "code-asset:" + assetId));
                }
            } catch (DuplicateKeyException concurrentReplay) {
                return findIssued(tenantId, itemNo);
            }
        }
        return Optional.empty();
    }

    @Override public Optional<IssuedCode> findIssued(String tenantId, String itemNo) {
        return jdbc.query("""
                SELECT code_asset_id FROM bc_code_asset
                WHERE tenant_id=? AND reserved_item_no=? AND status='ISSUED'
                """, (rs, row) -> new IssuedCode(rs.getString(1), "code-asset:" + rs.getString(1)),
                tenantId, itemNo).stream().findFirst();
    }

    @Override public boolean reverse(String tenantId, String itemNo, String operationNo) {
        return jdbc.update("""
                UPDATE bc_code_asset SET status='AVAILABLE',reserved_item_no=NULL,version=version+1
                WHERE tenant_id=? AND reserved_item_no=? AND status='ISSUED'
                """, tenantId, itemNo) == 1;
    }
}
