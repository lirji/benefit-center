package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.PhysicalFulfillmentRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

public final class JdbcPhysicalFulfillmentRepository implements PhysicalFulfillmentRepository {
    private final JdbcTemplate jdbc;
    public JdbcPhysicalFulfillmentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public IssueResult issue(String tenantId, String itemNo, String addressRef, String operationNo) {
        try {
            jdbc.update("""
                    INSERT INTO bc_physical_fulfillment
                    (tenant_id,item_no,address_ref,fulfillment_order_ref,shipment_ref,status,version)
                    VALUES (?,?,?,?,?,?,?)
                    """, tenantId, itemNo, addressRef, operationNo, null, "CREATED", 0);
        } catch (DuplicateKeyException replay) {
            // Stable item identity makes this an idempotent read.
        }
        return find(tenantId, itemNo).orElseThrow();
    }

    @Override public Optional<IssueResult> find(String tenantId, String itemNo) {
        return jdbc.query("""
                SELECT status,fulfillment_order_ref FROM bc_physical_fulfillment
                WHERE tenant_id=? AND item_no=?
                """, (rs, row) -> new IssueResult(rs.getString(1), rs.getString(2)),
                tenantId, itemNo).stream().findFirst();
    }

    @Override public boolean reverseBeforeShipment(String tenantId, String itemNo, String operationNo) {
        return jdbc.update("""
                UPDATE bc_physical_fulfillment SET status='CANCELLED',version=version+1
                WHERE tenant_id=? AND item_no=? AND status='CREATED' AND shipment_ref IS NULL
                """, tenantId, itemNo) == 1;
    }
}
