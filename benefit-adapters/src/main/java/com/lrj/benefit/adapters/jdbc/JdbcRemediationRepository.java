package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.RemediationRepository;
import com.lrj.benefit.contract.RemediationAction;
import com.lrj.benefit.domain.model.RemediationOrder;
import com.lrj.benefit.domain.model.RemediationStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class JdbcRemediationRepository implements RemediationRepository {
    private final JdbcTemplate jdbc;
    public JdbcRemediationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean insert(String tenantId, String sourceSystem, RemediationOrder order, String originalOperationNo,
                          String reason, String approvalRef, String requestHash) {
        try {
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO bc_remediation_order
                    (tenant_id,remediation_no,source_system,external_command_id,item_no,action_type,
                     original_operation_no,reason,approval_ref,status,version,created_at,updated_at,request_hash)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, tenantId, order.remediationNo(), sourceSystem, order.externalCommandId(), order.itemNo(),
                    order.action().name(), originalOperationNo, reason, approvalRef, order.status().name(),
                    order.version(), Timestamp.from(now), Timestamp.from(now), requestHash);
            return true;
        } catch (DuplicateKeyException replay) {
            return false;
        }
    }

    @Override public Optional<String> findCommandHash(String tenantId, String sourceSystem,
                                                      String externalCommandId) {
        List<String> values = jdbc.query("""
                SELECT request_hash FROM bc_remediation_order
                WHERE tenant_id=? AND source_system=? AND external_command_id=?
                """, (rs, row) -> rs.getString(1), tenantId, sourceSystem, externalCommandId);
        return values.stream().findFirst();
    }

    @Override public Optional<RemediationOrder> findByCommand(String tenantId, String sourceSystem,
                                                              String externalCommandId) {
        return query("tenant_id=? AND source_system=? AND external_command_id=?",
                tenantId, sourceSystem, externalCommandId);
    }

    @Override public Optional<RemediationOrder> find(String tenantId, String remediationNo) {
        return query("tenant_id=? AND remediation_no=?", tenantId, remediationNo);
    }

    @Override public Optional<String> findOriginalOperationNo(String tenantId, String remediationNo) {
        List<String> values = jdbc.query("""
                SELECT original_operation_no FROM bc_remediation_order
                WHERE tenant_id=? AND remediation_no=? AND original_operation_no IS NOT NULL
                """, (rs, row) -> rs.getString(1), tenantId, remediationNo);
        return values.stream().findFirst();
    }

    @Override public Optional<RemediationOrder> findByOperation(String tenantId, String operationNo) {
        List<String> ids = jdbc.query("""
                SELECT remediation_no FROM bc_fulfillment_operation
                WHERE tenant_id=? AND operation_no=? AND remediation_no IS NOT NULL
                """, (rs, row) -> rs.getString(1), tenantId, operationNo);
        return ids.isEmpty() ? Optional.empty() : find(tenantId, ids.getFirst());
    }

    @Override public boolean updateExpectedVersion(String tenantId, RemediationOrder order, long expectedVersion) {
        return jdbc.update("""
                UPDATE bc_remediation_order SET status=?,version=?,updated_at=?
                WHERE tenant_id=? AND remediation_no=? AND version=?
                """, order.status().name(), order.version(), Timestamp.from(Instant.now()), tenantId,
                order.remediationNo(), expectedVersion) == 1;
    }

    private Optional<RemediationOrder> query(String predicate, Object... args) {
        String sql = """
                SELECT remediation_no,external_command_id,item_no,action_type,status,version
                FROM bc_remediation_order WHERE %s
                """.formatted(predicate);
        List<RemediationOrder> values = jdbc.query(sql,
                (rs, row) -> new RemediationOrder(rs.getString("remediation_no"),
                        rs.getString("external_command_id"), rs.getString("item_no"),
                        RemediationAction.valueOf(rs.getString("action_type")),
                        RemediationStatus.valueOf(rs.getString("status")), rs.getLong("version")), args);
        return values.stream().findFirst();
    }
}
