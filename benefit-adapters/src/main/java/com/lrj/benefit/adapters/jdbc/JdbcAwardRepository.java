package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.AwardRepository;
import com.lrj.benefit.contract.BenefitType;
import com.lrj.benefit.domain.model.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class JdbcAwardRepository implements AwardRepository {
    private final JdbcTemplate jdbc;

    public JdbcAwardRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean insert(AwardOrder order) {
        Instant now = Instant.now();
        try {
            jdbc.update("""
                    INSERT INTO bc_award_order
                    (tenant_id,order_no,source_system,source_request_id,source_business_no,recipient_ref,
                     request_hash,partial_policy,status,home_cell,trace_id,version,created_at,updated_at,routing_key)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, order.tenantId(), order.orderNo(), order.sourceSystem(), order.sourceRequestId(),
                    order.sourceBusinessNo(), order.recipientRef(), order.requestHash(), "BEST_EFFORT",
                    order.status().name(), order.homeCell(), null, order.version(), ts(now), ts(now),
                    order.sourceRequestId());
            for (AwardItem item : order.items()) insertItem(order, item, now);
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    private void insertItem(AwardOrder order, AwardItem item, Instant now) {
        jdbc.update("""
                INSERT INTO bc_award_item
                (tenant_id,item_no,order_no,client_item_id,sku_id,benefit_type,amount_minor,currency,
                 quantity,status,route_id,failure_code,retry_at,version,created_at,updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, order.tenantId(), item.itemNo(), order.orderNo(), item.clientItemId(), item.skuId(),
                item.benefitType().name(), item.amountMinor(), item.currency(), item.quantity(), item.status().name(),
                item.routeId(), item.failureCode(), null, item.version(), ts(now), ts(now));
    }

    @Override public Optional<AwardOrder> findByOrderNo(String tenantId, String orderNo) {
        return queryOne("tenant_id=? AND order_no=?", tenantId, orderNo);
    }

    @Override public Optional<AwardOrder> findBySource(String tenantId, String sourceSystem, String sourceRequestId) {
        return queryOne("tenant_id=? AND source_system=? AND source_request_id=?",
                tenantId, sourceSystem, sourceRequestId);
    }

    @Override public Optional<AwardOrder> findByItemNo(String tenantId, String itemNo) {
        List<String> orderNos = jdbc.query("SELECT order_no FROM bc_award_item WHERE tenant_id=? AND item_no=?",
                (rs, row) -> rs.getString(1), tenantId, itemNo);
        return orderNos.isEmpty() ? Optional.empty() : findByOrderNo(tenantId, orderNos.getFirst());
    }

    private Optional<AwardOrder> queryOne(String predicate, Object... args) {
        List<OrderRow> orders = jdbc.query("""
                SELECT tenant_id,order_no,source_system,source_request_id,source_business_no,recipient_ref,
                       request_hash,home_cell,status,version
                FROM bc_award_order WHERE
                """ + predicate, this::mapOrder, args);
        if (orders.isEmpty()) return Optional.empty();
        OrderRow row = orders.getFirst();
        List<AwardItem> items = jdbc.query("""
                SELECT item_no,client_item_id,sku_id,benefit_type,quantity,amount_minor,currency,status,
                       route_id,failure_code,version
                FROM bc_award_item WHERE tenant_id=? AND order_no=? ORDER BY item_no
                """, this::mapItem, row.tenantId(), row.orderNo());
        return Optional.of(new AwardOrder(row.tenantId(), row.orderNo(), row.sourceSystem(), row.sourceRequestId(),
                row.sourceBusinessNo(), row.recipientRef(), row.requestHash(), row.homeCell(), items,
                row.status(), row.version()));
    }

    @Override
    public boolean updateExpectedVersion(AwardOrder order, long expectedVersion) {
        int updated = jdbc.update("""
                UPDATE bc_award_order SET status=?,version=?,updated_at=?
                WHERE tenant_id=? AND order_no=? AND version=?
                """, order.status().name(), order.version(), ts(Instant.now()), order.tenantId(), order.orderNo(),
                expectedVersion);
        if (updated != 1) return false;
        for (AwardItem item : order.items()) {
            jdbc.update("""
                    UPDATE bc_award_item SET status=?,route_id=?,failure_code=?,version=?,updated_at=?
                    WHERE tenant_id=? AND item_no=?
                    """, item.status().name(), item.routeId(), item.failureCode(), item.version(), ts(Instant.now()),
                    order.tenantId(), item.itemNo());
        }
        return true;
    }

    private OrderRow mapOrder(ResultSet rs, int row) throws SQLException {
        return new OrderRow(rs.getString("tenant_id"), rs.getString("order_no"), rs.getString("source_system"),
                rs.getString("source_request_id"), rs.getString("source_business_no"), rs.getString("recipient_ref"),
                rs.getString("request_hash"), rs.getString("home_cell"),
                AwardOrderStatus.valueOf(rs.getString("status")), rs.getLong("version"));
    }

    private AwardItem mapItem(ResultSet rs, int row) throws SQLException {
        Long amount = (Long) rs.getObject("amount_minor");
        return new AwardItem(rs.getString("item_no"), rs.getString("client_item_id"), rs.getString("sku_id"),
                BenefitType.valueOf(rs.getString("benefit_type")), rs.getLong("quantity"), amount,
                rs.getString("currency"), AwardItemStatus.valueOf(rs.getString("status")),
                rs.getString("route_id"), rs.getString("failure_code"), rs.getLong("version"));
    }

    private static Timestamp ts(Instant value) { return Timestamp.from(value); }

    private record OrderRow(String tenantId, String orderNo, String sourceSystem, String sourceRequestId,
                            String sourceBusinessNo, String recipientRef, String requestHash, String homeCell,
                            AwardOrderStatus status, long version) {}
}
