package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.IdGenerator;
import com.lrj.benefit.application.port.out.WalletRepository;
import com.lrj.benefit.domain.model.WalletAssetType;
import com.lrj.benefit.domain.model.WalletEntry;
import com.lrj.benefit.domain.model.WalletEntryStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

/** WalletEntry 与现金余额流水的同库事务实现。 */
public final class JdbcWalletRepository implements WalletRepository {
    private final JdbcTemplate jdbc;
    private final IdGenerator ids;
    private final Clock clock;

    public JdbcWalletRepository(JdbcTemplate jdbc, IdGenerator ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public WalletEntry createIfAbsent(WalletEntry entry, String operationNo) {
        try {
            jdbc.update("""
                    INSERT INTO bc_wallet_entry
                    (tenant_id,entry_id,subject_ref,sku_id,sku_version,award_order_no,item_no,asset_type,
                     status,version,expires_at,face_value_minor,currency,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, entry.tenantId(), entry.entryId(), entry.subjectRef(), entry.skuId(), entry.skuVersion(),
                    entry.awardOrderNo(), entry.itemNo(), entry.assetType().name(), entry.status().name(),
                    entry.version(), timestamp(entry.expiresAt()), entry.faceValueMinor(), entry.currency(),
                    timestamp(entry.createdAt()), timestamp(entry.createdAt()));
            if (entry.assetType() == WalletAssetType.CASH_BALANCE) {
                appendBalance(entry, operationNo, "ISSUE", entry.faceValueMinor());
            }
            return entry;
        } catch (DuplicateKeyException replay) {
            return findByItem(entry.tenantId(), entry.itemNo()).orElseThrow(() ->
                    new IllegalStateException("wallet idempotent winner is not visible"));
        }
    }

    @Override
    public Optional<WalletEntry> reverseByItem(String tenantId, String itemNo, String operationNo) {
        Optional<WalletEntry> existing = findByItem(tenantId, itemNo);
        if (existing.isEmpty()) return Optional.empty();
        WalletEntry entry = existing.get();
        int changed = jdbc.update("""
                UPDATE bc_wallet_entry SET status='REVERSED',version=version+1,updated_at=?
                WHERE tenant_id=? AND item_no=? AND status<>'REVERSED'
                """, Timestamp.from(clock.instant()), tenantId, itemNo);
        if (changed == 1 && entry.assetType() == WalletAssetType.CASH_BALANCE) {
            appendBalance(entry, operationNo, "REVERSAL", Math.negateExact(entry.faceValueMinor()));
        }
        return findByItem(tenantId, itemNo);
    }

    @Override
    public Optional<WalletEntry> findByItem(String tenantId, String itemNo) {
        List<WalletEntry> rows = jdbc.query("""
                SELECT tenant_id,entry_id,subject_ref,sku_id,sku_version,award_order_no,item_no,asset_type,
                       status,version,expires_at,face_value_minor,currency,created_at
                FROM bc_wallet_entry WHERE tenant_id=? AND item_no=?
                """, this::map, tenantId, itemNo);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<WalletEntry> findById(String tenantId, String entryId) {
        List<WalletEntry> rows = jdbc.query("""
                SELECT tenant_id,entry_id,subject_ref,sku_id,sku_version,award_order_no,item_no,asset_type,
                       status,version,expires_at,face_value_minor,currency,created_at
                FROM bc_wallet_entry WHERE tenant_id=? AND entry_id=?
                """, this::map, tenantId, entryId);
        return rows.stream().findFirst();
    }

    @Override
    public Optional<WalletEntry> compareAndSetStatus(String tenantId, String entryId,
            WalletEntryStatus currentStatus, long currentVersion, WalletEntryStatus targetStatus,
            java.time.Instant updatedAt) {
        int changed = jdbc.update("""
                UPDATE bc_wallet_entry SET status=?,version=version+1,updated_at=?
                WHERE tenant_id=? AND entry_id=? AND status=? AND version=?
                """, targetStatus.name(), Timestamp.from(updatedAt), tenantId, entryId,
                currentStatus.name(), currentVersion);
        return changed == 1 ? findById(tenantId, entryId) : Optional.empty();
    }

    @Override
    public void appendCommandBalance(WalletEntry entry, String operationNo,
                                     String entryType, long deltaMinor) {
        appendBalance(entry, operationNo, entryType, deltaMinor);
    }

    private void appendBalance(WalletEntry entry, String operationNo, String entryType, Long delta) {
        if (delta == null || entry.currency() == null) {
            throw new IllegalStateException("cash wallet entry is missing amount or currency");
        }
        jdbc.update("""
                INSERT INTO bc_wallet_balance_ledger
                (tenant_id,ledger_no,subject_ref,wallet_entry_id,operation_no,entry_type,delta_minor,currency,created_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, entry.tenantId(), ids.next("WL"), entry.subjectRef(), entry.entryId(), operationNo,
                entryType, delta, entry.currency(), Timestamp.from(clock.instant()));
    }

    private WalletEntry map(ResultSet rs, int row) throws SQLException {
        return new WalletEntry(rs.getString("tenant_id"), rs.getString("entry_id"),
                rs.getString("subject_ref"), rs.getString("sku_id"), rs.getLong("sku_version"),
                rs.getString("award_order_no"), rs.getString("item_no"),
                WalletAssetType.valueOf(rs.getString("asset_type")),
                WalletEntryStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                instant(rs.getTimestamp("expires_at")),
                (Long) rs.getObject("face_value_minor"), rs.getString("currency"),
                instant(rs.getTimestamp("created_at")));
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
