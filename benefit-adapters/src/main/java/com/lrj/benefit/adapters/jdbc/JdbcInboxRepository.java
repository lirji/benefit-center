package com.lrj.benefit.adapters.jdbc;

import com.lrj.benefit.application.port.out.InboxRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;

public final class JdbcInboxRepository implements InboxRepository {
    private final JdbcTemplate jdbc;
    public JdbcInboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public ClaimResult claim(String tenantId, String consumerGroup, String messageId, String payloadHash) {
        try {
            jdbc.update("""
                    INSERT INTO bc_inbox_message
                    (tenant_id,consumer_group,message_id,payload_hash,status,received_at,processed_at,lease_until)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, tenantId, consumerGroup, messageId, payloadHash, "PROCESSING",
                    Timestamp.from(Instant.now()), null, Timestamp.from(Instant.now().plusSeconds(60)));
            return ClaimResult.CLAIMED;
        } catch (DuplicateKeyException replay) {
            var existing = jdbc.queryForMap("""
                    SELECT payload_hash,status,lease_until FROM bc_inbox_message
                    WHERE tenant_id=? AND consumer_group=? AND message_id=?
                    """, tenantId, consumerGroup, messageId);
            if (!payloadHash.equals(existing.get("payload_hash"))) return ClaimResult.PAYLOAD_CONFLICT;
            String status = String.valueOf(existing.get("status"));
            Timestamp lease = (Timestamp) existing.get("lease_until");
            boolean reclaimable = "FAILED".equals(status)
                    || ("PROCESSING".equals(status) && lease != null && !lease.toInstant().isAfter(Instant.now()));
            if (reclaimable && jdbc.update("""
                    UPDATE bc_inbox_message SET status='PROCESSING',lease_until=?
                    WHERE tenant_id=? AND consumer_group=? AND message_id=? AND status=?
                    """, Timestamp.from(Instant.now().plusSeconds(60)), tenantId, consumerGroup, messageId,
                    status) == 1) return ClaimResult.CLAIMED;
            return ClaimResult.REPLAY;
        }
    }

    @Override public void markProcessed(String tenantId, String consumerGroup, String messageId) {
        jdbc.update("""
                UPDATE bc_inbox_message SET status='PROCESSED',processed_at=?,lease_until=NULL
                WHERE tenant_id=? AND consumer_group=? AND message_id=?
                """, Timestamp.from(Instant.now()), tenantId, consumerGroup, messageId);
    }

    @Override public void markFailed(String tenantId, String consumerGroup, String messageId) {
        jdbc.update("""
                UPDATE bc_inbox_message SET status='FAILED',lease_until=NULL
                WHERE tenant_id=? AND consumer_group=? AND message_id=?
                """, tenantId, consumerGroup, messageId);
    }
}
