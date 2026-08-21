package com.lrj.benefit.adapters.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.port.out.OutboxRepository;
import com.lrj.benefit.contract.MessageEnvelope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

public final class JdbcOutboxRepository implements OutboxRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcOutboxRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override public boolean enqueue(MessageEnvelope<?> event) {
        try {
            String payload = serialize(event);
            String normalizedHash = sha256(payload);
            jdbc.update("""
                    INSERT INTO bc_outbox_event
                    (tenant_id,event_id,aggregate_type,aggregate_id,event_type,schema_version,payload_hash,payload,
                     status,attempt_count,next_attempt_at,published_at,created_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, event.tenantId(), event.eventId(), aggregateType(event.eventType()), event.partitionKey(),
                    event.eventType(), event.schemaVersion(), normalizedHash, payload,
                    "PENDING", 0, Timestamp.from(event.occurredAt()), null, Timestamp.from(event.occurredAt()));
            return true;
        } catch (DuplicateKeyException replay) {
            String existingHash = jdbc.queryForObject("""
                    SELECT payload_hash FROM bc_outbox_event WHERE tenant_id=? AND event_id=?
                    """, String.class, event.tenantId(), event.eventId());
            String payload = serialize(event);
            if (!sha256(payload).equals(existingHash)) {
                throw new IllegalStateException("outbox event id was reused with another payload");
            }
            return false;
        }
    }

    @Override public List<OutboxMessage> claimDue(String workerId, Instant now, int limit) {
        jdbc.update("""
                UPDATE bc_outbox_event SET status='FAILED',lease_owner=NULL,lease_until=NULL
                WHERE status='SENDING' AND lease_until<?
                """, Timestamp.from(now));
        List<OutboxMessage> candidates = jdbc.query("""
                SELECT tenant_id,event_id,event_type,aggregate_id,payload,attempt_count
                FROM bc_outbox_event
                WHERE status IN ('PENDING','FAILED') AND (next_attempt_at IS NULL OR next_attempt_at<=?)
                ORDER BY created_at,event_id LIMIT ?
                """, (rs, row) -> new OutboxMessage(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getInt(6)), Timestamp.from(now), limit);
        return candidates.stream().filter(message -> jdbc.update("""
                UPDATE bc_outbox_event SET status='SENDING',lease_owner=?,lease_until=?,attempt_count=attempt_count+1
                WHERE tenant_id=? AND event_id=? AND status IN ('PENDING','FAILED')
                """, workerId, Timestamp.from(now.plusSeconds(60)), message.tenantId(), message.eventId()) == 1).toList();
    }

    @Override public void markPublished(String tenantId, String eventId, String workerId, Instant publishedAt) {
        requireOne(jdbc.update("""
                UPDATE bc_outbox_event SET status='PUBLISHED',published_at=?,lease_owner=NULL,lease_until=NULL
                WHERE tenant_id=? AND event_id=? AND status='SENDING' AND lease_owner=?
                """, Timestamp.from(publishedAt), tenantId, eventId, workerId));
    }

    @Override public void markFailed(String tenantId, String eventId, String workerId,
                                     Instant nextAttemptAt, int maxAttempts) {
        requireOne(jdbc.update("""
                UPDATE bc_outbox_event
                SET status=CASE WHEN attempt_count>=? THEN 'DEAD' ELSE 'FAILED' END,next_attempt_at=?,
                    lease_owner=NULL,lease_until=NULL
                WHERE tenant_id=? AND event_id=? AND status='SENDING' AND lease_owner=?
                """, maxAttempts, Timestamp.from(nextAttemptAt), tenantId, eventId, workerId));
    }

    private static String aggregateType(String eventType) {
        return eventType.startsWith("REMEDIATION") ? "REMEDIATION" : "AWARD_ORDER";
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private String serialize(MessageEnvelope<?> event) {
        try {
            return json.writeValueAsString(event);
        } catch (Exception serialization) {
            throw new IllegalArgumentException("event cannot be serialized", serialization);
        }
    }

    private static void requireOne(int changed) {
        if (changed != 1) throw new IllegalStateException("outbox lease was lost");
    }
}
