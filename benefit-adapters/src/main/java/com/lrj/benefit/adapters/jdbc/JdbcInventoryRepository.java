package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.IdGenerator;
import com.lrj.benefit.application.port.out.InventoryRepository;
import com.lrj.benefit.domain.model.InventoryOwnerType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class JdbcInventoryRepository implements InventoryRepository {
    private final JdbcTemplate jdbc;
    private final IdGenerator ids;

    public JdbcInventoryRepository(JdbcTemplate jdbc, IdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override public boolean reserve(String tenantId, String accountId, long quantity, long expectedVersion) {
        return jdbc.update("""
                UPDATE bc_inventory_account SET available=available-?,reserved=reserved+?,version=version+1
                WHERE tenant_id=? AND account_id=? AND version=? AND available>=? AND owner_type<>'CHANNEL_SHADOW'
                """, quantity, quantity, tenantId, accountId, expectedVersion, quantity) == 1;
    }

    @Override public boolean commit(String tenantId, String accountId, long quantity, long expectedVersion) {
        return jdbc.update("""
                UPDATE bc_inventory_account SET reserved=reserved-?,issued=issued+?,version=version+1
                WHERE tenant_id=? AND account_id=? AND version=? AND reserved>=? AND owner_type<>'CHANNEL_SHADOW'
                """, quantity, quantity, tenantId, accountId, expectedVersion, quantity) == 1;
    }

    @Override public boolean release(String tenantId, String accountId, long quantity, long expectedVersion) {
        return jdbc.update("""
                UPDATE bc_inventory_account SET reserved=reserved-?,available=available+?,version=version+1
                WHERE tenant_id=? AND account_id=? AND version=? AND reserved>=? AND owner_type<>'CHANNEL_SHADOW'
                """, quantity, quantity, tenantId, accountId, expectedVersion, quantity) == 1;
    }

    @Override public boolean returnIssued(String tenantId, String accountId, long quantity, long expectedVersion) {
        return jdbc.update("""
                UPDATE bc_inventory_account SET issued=issued-?,available=available+?,version=version+1
                WHERE tenant_id=? AND account_id=? AND version=? AND issued>=? AND owner_type<>'CHANNEL_SHADOW'
                """, quantity, quantity, tenantId, accountId, expectedVersion, quantity) == 1;
    }

    @Override
    public boolean reserveAvailable(String tenantId, String skuId, InventoryOwnerType ownerType, long quantity,
                                    String itemNo, String operationNo) {
        if (ownerType == InventoryOwnerType.CHANNEL_SHADOW) {
            throw new IllegalArgumentException("channel shadow cannot be reserved");
        }
        List<String> candidates = jdbc.query("""
                SELECT account_id FROM bc_inventory_account
                WHERE tenant_id=? AND sku_id=? AND owner_type=? AND available>=?
                ORDER BY account_id
                """, (rs, row) -> rs.getString(1), tenantId, skuId, ownerType.name(), quantity);
        for (String accountId : candidates) {
            int changed = jdbc.update("""
                    UPDATE bc_inventory_account SET available=available-?,reserved=reserved+?,version=version+1
                    WHERE tenant_id=? AND account_id=? AND available>=? AND owner_type=?
                    """, quantity, quantity, tenantId, accountId, quantity, ownerType.name());
            if (changed == 1) {
                appendInventoryLedger(tenantId, accountId, itemNo, operationNo, "RESERVE",
                        -quantity, quantity, 0);
                return true;
            }
        }
        return false;
    }

    @Override
    public void commitReservations(String tenantId, String operationNo, Set<InventoryOwnerType> ownerTypes) {
        for (Reservation reservation : reservations(tenantId, operationNo, ownerTypes)) {
            if (appendInventoryLedger(tenantId, reservation.accountId(), reservation.itemNo(), operationNo,
                    "COMMIT", 0, -reservation.quantity(), reservation.quantity())) {
                int changed = jdbc.update("""
                        UPDATE bc_inventory_account SET reserved=reserved-?,issued=issued+?,version=version+1
                        WHERE tenant_id=? AND account_id=? AND reserved>=? AND owner_type<>'CHANNEL_SHADOW'
                        """, reservation.quantity(), reservation.quantity(), tenantId, reservation.accountId(),
                        reservation.quantity());
                if (changed != 1) throw new IllegalStateException("inventory commit invariant failed");
            }
        }
    }

    @Override
    public void releaseReservations(String tenantId, String operationNo, Set<InventoryOwnerType> ownerTypes) {
        for (Reservation reservation : reservations(tenantId, operationNo, ownerTypes)) {
            if (appendInventoryLedger(tenantId, reservation.accountId(), reservation.itemNo(), operationNo,
                    "RELEASE", reservation.quantity(), -reservation.quantity(), 0)) {
                int changed = jdbc.update("""
                        UPDATE bc_inventory_account SET reserved=reserved-?,available=available+?,version=version+1
                        WHERE tenant_id=? AND account_id=? AND reserved>=? AND owner_type<>'CHANNEL_SHADOW'
                        """, reservation.quantity(), reservation.quantity(), tenantId, reservation.accountId(),
                        reservation.quantity());
                if (changed != 1) throw new IllegalStateException("inventory release invariant failed");
            }
        }
    }

    @Override
    public void returnIssued(String tenantId, String skuId, long quantity, Set<InventoryOwnerType> ownerTypes,
                             String itemNo, String operationNo) {
        for (InventoryOwnerType ownerType : ownerTypes) {
            long remaining = quantity;
            List<AccountIssued> accounts = jdbc.query("""
                    SELECT a.account_id,a.issued FROM bc_inventory_account a
                    WHERE a.tenant_id=? AND a.sku_id=? AND a.owner_type=? AND a.issued>0
                      AND EXISTS (SELECT 1 FROM bc_inventory_ledger l
                                  WHERE l.tenant_id=a.tenant_id AND l.account_id=a.account_id
                                    AND l.item_no=? AND l.entry_type='COMMIT')
                    ORDER BY a.account_id
                    """, (rs, row) -> new AccountIssued(rs.getString(1), rs.getLong(2)),
                    tenantId, skuId, ownerType.name(), itemNo);
            for (AccountIssued account : accounts) {
                if (remaining == 0) break;
                long amount = Math.min(remaining, account.issued());
                if (appendInventoryLedger(tenantId, account.accountId(), itemNo, operationNo,
                        "RETURN", amount, 0, -amount)) {
                    int changed = jdbc.update("""
                            UPDATE bc_inventory_account SET issued=issued-?,available=available+?,version=version+1
                            WHERE tenant_id=? AND account_id=? AND issued>=? AND owner_type=?
                            """, amount, amount, tenantId, account.accountId(), amount, ownerType.name());
                    if (changed != 1) throw new IllegalStateException("inventory return invariant failed");
                }
                remaining -= amount;
            }
            if (remaining != 0) throw new IllegalStateException("issued inventory lineage is insufficient");
        }
    }

    private List<Reservation> reservations(String tenantId, String operationNo, Set<InventoryOwnerType> ownerTypes) {
        if (ownerTypes.isEmpty()) return List.of();
        List<Reservation> result = new ArrayList<>();
        for (InventoryOwnerType ownerType : ownerTypes) {
            result.addAll(jdbc.query("""
                    SELECT l.account_id,l.item_no,l.delta_reserved
                    FROM bc_inventory_ledger l JOIN bc_inventory_account a
                      ON a.tenant_id=l.tenant_id AND a.account_id=l.account_id
                    WHERE l.tenant_id=? AND l.operation_no=? AND l.entry_type='RESERVE' AND a.owner_type=?
                    """, (rs, row) -> new Reservation(rs.getString(1), rs.getString(2), rs.getLong(3)),
                    tenantId, operationNo, ownerType.name()));
        }
        return result;
    }

    private boolean appendInventoryLedger(String tenantId, String accountId, String itemNo, String operationNo,
                                          String entryType, long available, long reserved, long issued) {
        try {
            jdbc.update("""
                    INSERT INTO bc_inventory_ledger
                    (tenant_id,ledger_no,account_id,item_no,operation_no,entry_type,
                     delta_available,delta_reserved,delta_issued,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, tenantId, ids.next("IL"), accountId, itemNo, operationNo, entryType,
                    available, reserved, issued, Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private record Reservation(String accountId, String itemNo, long quantity) {}
    private record AccountIssued(String accountId, long issued) {}
}
