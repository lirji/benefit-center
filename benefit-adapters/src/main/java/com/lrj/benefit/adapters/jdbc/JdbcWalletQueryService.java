package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.in.WalletQueryUseCase;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import com.lrj.benefit.domain.model.WalletEntryStatus;

/** 客服券包读模型，按 subject 聚簇并使用 entry_id seek 分页。 */
public final class JdbcWalletQueryService implements WalletQueryUseCase {
    private static final int MAX_LIMIT = 50;
    private final JdbcTemplate jdbc;

    public JdbcWalletQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public WalletView wallet(String tenantId, String subjectRef) {
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_wallet_entry WHERE tenant_id=? AND subject_ref=?
                """, Long.class, tenantId, subjectRef);
        Long unused = jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_wallet_entry WHERE tenant_id=? AND subject_ref=? AND status='UNUSED'
                """, Long.class, tenantId, subjectRef);
        List<CashBalanceView> balances = jdbc.query("""
                SELECT currency,SUM(delta_minor) AS balance_minor
                FROM bc_wallet_balance_ledger WHERE tenant_id=? AND subject_ref=?
                GROUP BY currency ORDER BY currency
                """, (rs, row) -> new CashBalanceView(rs.getString("currency"), rs.getLong("balance_minor")),
                tenantId, subjectRef);
        return new WalletView(subjectRef, total == null ? 0 : total, unused == null ? 0 : unused, balances);
    }

    @Override
    public List<WalletEntryView> entries(String tenantId, String subjectRef, String status,
                                         String skuId, String afterEntryId, int limit) {
        String normalizedStatus = walletStatus(status);
        String normalizedSku = blankToNull(skuId);
        String after = blankToNull(afterEntryId);
        return jdbc.query("""
                SELECT entry_id,subject_ref,sku_id,sku_version,award_order_no,item_no,asset_type,status,
                       version,expires_at,face_value_minor,currency,created_at
                FROM bc_wallet_entry
                WHERE tenant_id=? AND subject_ref=?
                  AND (? IS NULL OR status=?)
                  AND (? IS NULL OR sku_id=?)
                  AND (? IS NULL OR entry_id>?)
                ORDER BY entry_id LIMIT ?
                """, this::mapEntry, tenantId, subjectRef, normalizedStatus, normalizedStatus,
                normalizedSku, normalizedSku, after, after, bound(limit));
    }

    private WalletEntryView mapEntry(ResultSet rs, int row) throws SQLException {
        return new WalletEntryView(rs.getString("entry_id"), rs.getString("subject_ref"),
                rs.getString("sku_id"), rs.getLong("sku_version"), rs.getString("award_order_no"),
                rs.getString("item_no"), rs.getString("asset_type"), rs.getString("status"),
                rs.getLong("version"), instant(rs.getTimestamp("expires_at")),
                (Long) rs.getObject("face_value_minor"),
                rs.getString("currency"), instant(rs.getTimestamp("created_at")));
    }

    private static int bound(int limit) {
        if (limit < 1) return 20;
        return Math.min(limit, MAX_LIMIT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String walletStatus(String value) {
        String normalized = blankToNull(value);
        return normalized == null ? null : WalletEntryStatus.valueOf(normalized).name();
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
