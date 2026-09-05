package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.UserLimitRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** 用户限额真账 JDBC 实现；条件更新在数据库内串行化同一用户/SKU/周期热点。 */
public final class JdbcUserLimitRepository implements UserLimitRepository {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcUserLimitRepository(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public boolean reserve(String tenantId, String subjectRef, String skuId, String itemNo,
                           long quantity, Long dailyLimit, Long totalLimit, LocalDate businessDate) {
        if (quantity <= 0) throw new IllegalArgumentException("limit quantity must be positive");
        if (dailyLimit != null && !reserveOne(tenantId, subjectRef, skuId, itemNo, quantity,
                dailyLimit, PeriodType.DAY, businessDate.toString())) return false;
        return totalLimit == null || reserveOne(tenantId, subjectRef, skuId, itemNo, quantity,
                totalLimit, PeriodType.TOTAL, "ALL");
    }

    private boolean reserveOne(String tenantId, String subjectRef, String skuId, String itemNo,
                               long quantity, long configuredLimit, PeriodType periodType, String periodKey) {
        if (configuredLimit <= 0) throw new IllegalArgumentException("configured user limit must be positive");
        String existingStatus = reservationStatus(tenantId, itemNo, periodType);
        if ("RESERVED".equals(existingStatus) || "ISSUED".equals(existingStatus)) return true;
        ensureCounter(tenantId, subjectRef, skuId, periodType, periodKey);
        int changed = jdbc.update("""
                UPDATE bc_user_limit_counter
                SET reserved_count=reserved_count+?,version=version+1,updated_at=?
                WHERE tenant_id=? AND subject_ref=? AND sku_id=? AND period_type=? AND period_key=?
                  AND reserved_count+issued_count<=?
                """, quantity, now(), tenantId, subjectRef, skuId, periodType.name(), periodKey,
                configuredLimit - quantity);
        if (changed != 1) return false;
        if (existingStatus == null) {
            jdbc.update("""
                    INSERT INTO bc_user_limit_reservation
                    (tenant_id,item_no,period_type,period_key,subject_ref,sku_id,quantity,status,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, tenantId, itemNo, periodType.name(), periodKey, subjectRef, skuId, quantity,
                    "RESERVED", now(), now());
        } else {
            int reused = jdbc.update("""
                    UPDATE bc_user_limit_reservation
                    SET period_key=?,subject_ref=?,sku_id=?,quantity=?,status='RESERVED',updated_at=?
                    WHERE tenant_id=? AND item_no=? AND period_type=? AND status IN ('RELEASED','REVERSED')
                    """, periodKey, subjectRef, skuId, quantity, now(), tenantId, itemNo, periodType.name());
            requireChanged(reused, "user limit reservation cannot be reused from its current state");
        }
        return true;
    }

    private String reservationStatus(String tenantId, String itemNo, PeriodType periodType) {
        List<String> values = jdbc.query("""
                SELECT status FROM bc_user_limit_reservation
                WHERE tenant_id=? AND item_no=? AND period_type=?
                """, (rs, row) -> rs.getString("status"), tenantId, itemNo, periodType.name());
        return values.isEmpty() ? null : values.getFirst();
    }

    private void ensureCounter(String tenantId, String subjectRef, String skuId,
                               PeriodType periodType, String periodKey) {
        try {
            jdbc.update("""
                    INSERT INTO bc_user_limit_counter
                    (tenant_id,subject_ref,sku_id,period_type,period_key,reserved_count,issued_count,version,updated_at)
                    VALUES (?,?,?,?,?,0,0,0,?)
                    """, tenantId, subjectRef, skuId, periodType.name(), periodKey, now());
        } catch (DuplicateKeyException ignored) {
            // 并发首单只有一个线程建行，其余线程继续走条件更新。
        }
    }

    @Override
    public void markIssued(String tenantId, String itemNo) {
        for (Reservation row : reservations(tenantId, itemNo, "RESERVED")) {
            if (changeReservationStatus(tenantId, itemNo, row.periodType(), "RESERVED", "ISSUED")) {
                int changed = jdbc.update("""
                        UPDATE bc_user_limit_counter
                        SET reserved_count=reserved_count-?,issued_count=issued_count+?,version=version+1,updated_at=?
                        WHERE tenant_id=? AND subject_ref=? AND sku_id=? AND period_type=? AND period_key=?
                          AND reserved_count>=?
                        """, row.quantity(), row.quantity(), now(), tenantId, row.subjectRef(), row.skuId(),
                        row.periodType().name(), row.periodKey(), row.quantity());
                requireChanged(changed, "user limit issue invariant failed");
            }
        }
    }

    @Override
    public void release(String tenantId, String itemNo) {
        for (Reservation row : reservations(tenantId, itemNo, "RESERVED")) {
            if (changeReservationStatus(tenantId, itemNo, row.periodType(), "RESERVED", "RELEASED")) {
                int changed = jdbc.update("""
                        UPDATE bc_user_limit_counter
                        SET reserved_count=reserved_count-?,version=version+1,updated_at=?
                        WHERE tenant_id=? AND subject_ref=? AND sku_id=? AND period_type=? AND period_key=?
                          AND reserved_count>=?
                        """, row.quantity(), now(), tenantId, row.subjectRef(), row.skuId(),
                        row.periodType().name(), row.periodKey(), row.quantity());
                requireChanged(changed, "user limit release invariant failed");
            }
        }
    }

    @Override
    public void reverse(String tenantId, String itemNo) {
        for (Reservation row : reservations(tenantId, itemNo, "ISSUED")) {
            if (changeReservationStatus(tenantId, itemNo, row.periodType(), "ISSUED", "REVERSED")) {
                int changed = jdbc.update("""
                        UPDATE bc_user_limit_counter
                        SET issued_count=issued_count-?,version=version+1,updated_at=?
                        WHERE tenant_id=? AND subject_ref=? AND sku_id=? AND period_type=? AND period_key=?
                          AND issued_count>=?
                        """, row.quantity(), now(), tenantId, row.subjectRef(), row.skuId(),
                        row.periodType().name(), row.periodKey(), row.quantity());
                requireChanged(changed, "user limit reversal invariant failed");
            }
        }
    }

    @Override
    public long currentUsage(String tenantId, String subjectRef, String skuId,
                             PeriodType periodType, String periodKey) {
        List<Long> values = jdbc.query("""
                SELECT reserved_count+issued_count AS usage_count
                FROM bc_user_limit_counter
                WHERE tenant_id=? AND subject_ref=? AND sku_id=? AND period_type=? AND period_key=?
                """, (rs, row) -> rs.getLong("usage_count"), tenantId, subjectRef, skuId,
                periodType.name(), periodKey);
        return values.isEmpty() ? 0 : values.getFirst();
    }

    @Override
    public List<CounterKey> counterKeysForItem(String tenantId, String itemNo) {
        return jdbc.query("""
                SELECT subject_ref,sku_id,period_type,period_key
                FROM bc_user_limit_reservation WHERE tenant_id=? AND item_no=?
                """, (rs, row) -> new CounterKey(rs.getString("subject_ref"), rs.getString("sku_id"),
                PeriodType.valueOf(rs.getString("period_type")), rs.getString("period_key")), tenantId, itemNo);
    }

    private List<Reservation> reservations(String tenantId, String itemNo, String status) {
        return jdbc.query("""
                SELECT subject_ref,sku_id,period_type,period_key,quantity
                FROM bc_user_limit_reservation WHERE tenant_id=? AND item_no=? AND status=?
                """, (rs, row) -> new Reservation(rs.getString("subject_ref"), rs.getString("sku_id"),
                PeriodType.valueOf(rs.getString("period_type")), rs.getString("period_key"),
                rs.getLong("quantity")), tenantId, itemNo, status);
    }

    private boolean changeReservationStatus(String tenantId, String itemNo, PeriodType periodType,
                                            String expected, String target) {
        return jdbc.update("""
                UPDATE bc_user_limit_reservation SET status=?,updated_at=?
                WHERE tenant_id=? AND item_no=? AND period_type=? AND status=?
                """, target, now(), tenantId, itemNo, periodType.name(), expected) == 1;
    }

    private Timestamp now() {
        return Timestamp.from(clock.instant());
    }

    private static void requireChanged(int changed, String message) {
        if (changed != 1) throw new IllegalStateException(message);
    }

    private record Reservation(String subjectRef, String skuId, PeriodType periodType,
                               String periodKey, long quantity) {}
}
