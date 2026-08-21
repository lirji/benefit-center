package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.IdGenerator;
import com.lrj.benefit.application.port.out.OperationRepository;
import com.lrj.benefit.domain.model.FulfillmentOperation;
import com.lrj.benefit.domain.model.OperationStatus;
import com.lrj.benefit.domain.model.OperationType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class JdbcOperationRepository implements OperationRepository {
    private final JdbcTemplate jdbc;
    private final IdGenerator ids;

    public JdbcOperationRepository(JdbcTemplate jdbc, IdGenerator ids) {
        this.jdbc = jdbc;
        this.ids = ids;
    }

    @Override public boolean insert(FulfillmentOperation operation) {
        try {
            Instant now = Instant.now();
            jdbc.update("""
                    INSERT INTO bc_fulfillment_operation
                    (tenant_id,operation_no,item_no,operation_type,remediation_no,status,idempotency_key,
                     lease_owner,lease_until,attempt_count,unknown_since,version,created_at,updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, operation.tenantId(), operation.operationNo(), operation.itemNo(), operation.type().name(),
                    operation.remediationNo(), operation.status().name(), operation.idempotencyKey(),
                    operation.leaseOwner(), ts(operation.leaseUntil()), 0, null, operation.version(), ts(now), ts(now));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }

    @Override public Optional<FulfillmentOperation> find(String tenantId, String operationNo) {
        List<FulfillmentOperation> values = jdbc.query("""
                SELECT tenant_id,operation_no,item_no,operation_type,idempotency_key,remediation_no,status,
                       lease_owner,lease_until,version
                FROM bc_fulfillment_operation WHERE tenant_id=? AND operation_no=?
                """, this::map, tenantId, operationNo);
        return values.stream().findFirst();
    }

    @Override public List<FulfillmentOperation> findDue(String tenantId, Instant now, int limit) {
        // A worker that died before I/O is safe to retry. A worker that died after DISPATCHING is UNKNOWN,
        // therefore the next owner must query the same operation instead of issuing again.
        jdbc.update("""
                UPDATE bc_fulfillment_operation SET status='CREATED',lease_owner=NULL,lease_until=NULL,version=version+1
                WHERE tenant_id=? AND status='LEASED' AND lease_until<?
                """, tenantId, ts(now));
        jdbc.update("""
                UPDATE bc_fulfillment_operation SET status='UNKNOWN',lease_owner=NULL,lease_until=NULL,
                       unknown_since=COALESCE(unknown_since,?),version=version+1
                WHERE tenant_id=? AND status IN ('DISPATCHING','QUERYING') AND lease_until<?
                """, ts(now), tenantId, ts(now));
        return jdbc.query("""
                SELECT tenant_id,operation_no,item_no,operation_type,idempotency_key,remediation_no,status,
                       lease_owner,lease_until,version
                FROM bc_fulfillment_operation
                WHERE tenant_id=? AND status IN ('CREATED','FAILED_RETRYABLE','UNKNOWN')
                  AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                ORDER BY updated_at,operation_no LIMIT ?
                """, this::map, tenantId, ts(now), limit);
    }

    @Override
    public boolean claimLease(String tenantId, String operationNo, String owner, Instant now, Instant until,
                              long expectedVersion) {
        return jdbc.update("""
                UPDATE bc_fulfillment_operation
                SET status='LEASED',lease_owner=?,lease_until=?,next_attempt_at=NULL,
                    attempt_count=attempt_count+1,version=version+1,updated_at=?
                WHERE tenant_id=? AND operation_no=? AND version=?
                  AND status IN ('CREATED','FAILED_RETRYABLE','UNKNOWN')
                  AND (lease_until IS NULL OR lease_until<=?)
                """, owner, ts(until), ts(now), tenantId, operationNo, expectedVersion, ts(now)) == 1;
    }

    @Override
    public boolean updateExpectedState(String tenantId, FulfillmentOperation operation, long expectedVersion) {
        Instant now = Instant.now();
        Timestamp nextAttempt = switch (operation.status()) {
            case UNKNOWN -> ts(now.plusSeconds(30));
            case FAILED_RETRYABLE -> ts(now.plusSeconds(2));
            default -> null;
        };
        return jdbc.update("""
                UPDATE bc_fulfillment_operation
                SET status=?,lease_owner=?,lease_until=?,
                    unknown_since=CASE WHEN ?='UNKNOWN' THEN COALESCE(unknown_since,?) ELSE unknown_since END,
                    next_attempt_at=?,version=?,updated_at=?
                WHERE tenant_id=? AND operation_no=? AND version=?
                """, operation.status().name(), operation.leaseOwner(), ts(operation.leaseUntil()),
                operation.status().name(), ts(now), nextAttempt, operation.version(), ts(now),
                tenantId, operation.operationNo(), expectedVersion) == 1;
    }

    @Override
    public void recordAttempt(String tenantId, String operationNo, String channelCode, String routeId,
                              String channelRequestNo, String providerReference, String status, String errorCode) {
        Integer sequence = jdbc.queryForObject("""
                SELECT COALESCE(MAX(sequence_no),0)+1 FROM bc_fulfillment_attempt
                WHERE tenant_id=? AND operation_no=?
                """, Integer.class, tenantId, operationNo);
        try {
            jdbc.update("""
                    INSERT INTO bc_fulfillment_attempt
                    (tenant_id,attempt_no,operation_no,sequence_no,channel_code,route_id,channel_request_no,
                     provider_reference,status,error_code,request_redacted,response_redacted,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, tenantId, ids.next("AT"), operationNo, sequence, channelCode, routeId,
                    channelRequestNo + ':' + sequence, providerReference, status, errorCode, null, null,
                    ts(Instant.now()));
        } catch (DuplicateKeyException ignoredReplay) {
            // An attempt is diagnostic; operation CAS and channel idempotency remain authoritative.
        }
    }

    @Override public int markDeadRetryable(String tenantId, Instant dueBefore, int maxAttempts) {
        return jdbc.update("""
                UPDATE bc_fulfillment_operation SET status='FAILED_FINAL',version=version+1,updated_at=?
                WHERE tenant_id=? AND status='FAILED_RETRYABLE' AND updated_at<? AND attempt_count>=?
                """, ts(Instant.now()), tenantId, ts(dueBefore), maxAttempts);
    }

    private FulfillmentOperation map(ResultSet rs, int row) throws SQLException {
        Timestamp until = rs.getTimestamp("lease_until");
        return new FulfillmentOperation(rs.getString("tenant_id"), rs.getString("operation_no"),
                rs.getString("item_no"), OperationType.valueOf(rs.getString("operation_type")),
                rs.getString("idempotency_key"), rs.getString("remediation_no"),
                OperationStatus.valueOf(rs.getString("status")), rs.getString("lease_owner"),
                until == null ? null : until.toInstant(), rs.getLong("version"));
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
}
