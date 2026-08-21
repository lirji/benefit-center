package com.lrj.benefit.adapters.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.port.out.UnitOfWork;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

public final class ChannelCallbackService {
    private final ChannelCallbackVerifier verifier;
    private final JdbcTemplate jdbc;
    private final UnitOfWork unitOfWork;
    private final ObjectMapper json;

    public ChannelCallbackService(ChannelCallbackVerifier verifier, JdbcTemplate jdbc,
                                  UnitOfWork unitOfWork, ObjectMapper json) {
        this.verifier = verifier;
        this.jdbc = jdbc;
        this.unitOfWork = unitOfWork;
        this.json = json;
    }

    public CallbackReceipt receive(String channelCode, String timestamp, String nonce,
                                   String signature, String body) {
        verifier.verify(channelCode, timestamp, nonce, body, signature);
        try {
            JsonNode payload = json.readTree(body);
            String tenantId = required(payload, "tenantId");
            String eventId = required(payload, "eventId");
            String operationNo = required(payload, "operationNo");
            String payloadHash = sha256(body);
            return unitOfWork.required(() -> store(channelCode, tenantId, eventId, operationNo, payloadHash));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception invalidJson) {
            throw new IllegalArgumentException("invalid callback JSON", invalidJson);
        }
    }

    private CallbackReceipt store(String channelCode, String tenantId, String eventId,
                                  String operationNo, String payloadHash) {
        try {
            jdbc.update("""
                    INSERT INTO bc_channel_callback
                    (tenant_id,channel_code,callback_event_id,channel_request_no,payload_hash,status,received_at,processed_at)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, tenantId, channelCode, eventId, operationNo, payloadHash, "RECEIVED",
                    Timestamp.from(Instant.now()), null);
        } catch (DuplicateKeyException replay) {
            String existing = jdbc.queryForObject("""
                    SELECT payload_hash FROM bc_channel_callback
                    WHERE tenant_id=? AND channel_code=? AND callback_event_id=?
                    """, String.class, tenantId, channelCode, eventId);
            if (!payloadHash.equals(existing)) throw new IllegalArgumentException("callback event payload conflict");
            return new CallbackReceipt(tenantId, eventId, true);
        }
        // The callback is evidence, not authority to guess a transition. UNKNOWN operations are queried by the worker.
        jdbc.update("""
                UPDATE bc_channel_callback SET status='PROCESSED',processed_at=?
                WHERE tenant_id=? AND channel_code=? AND callback_event_id=?
                """, Timestamp.from(Instant.now()), tenantId, channelCode, eventId);
        jdbc.update("""
                UPDATE bc_fulfillment_operation SET next_attempt_at=?
                WHERE tenant_id=? AND operation_no=? AND status='UNKNOWN'
                """, Timestamp.from(Instant.now()), tenantId, operationNo);
        return new CallbackReceipt(tenantId, eventId, false);
    }

    private static String required(JsonNode value, String field) {
        String text = value.path(field).asText(null);
        if (text == null || text.isBlank()) throw new IllegalArgumentException("callback field is required: " + field);
        return text;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    public record CallbackReceipt(String tenantId, String eventId, boolean replay) {}
}
