package com.lrj.benefit.adapters.jdbc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.port.in.IdempotentCommandExecutor;
import com.lrj.benefit.application.port.out.UnitOfWork;
import com.lrj.benefit.application.service.BenefitApplicationException;
import com.lrj.benefit.contract.BenefitErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;

/** 写命令幂等表实现，记录与领域修改处在同一本地事务。 */
public final class JdbcIdempotentCommandExecutor implements IdempotentCommandExecutor {
    private final JdbcTemplate jdbc;
    private final UnitOfWork unitOfWork;
    private final Clock clock;
    private final ObjectMapper json;

    public JdbcIdempotentCommandExecutor(JdbcTemplate jdbc, UnitOfWork unitOfWork, Clock clock,
                                         ObjectMapper json) {
        this.jdbc = jdbc;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public <T> T executeForResult(String tenantId, String idempotencyKey, String operationName,
                                  String payloadHash, Supplier<T> command, Class<T> resultType) {
        require("tenantId", tenantId, 64);
        require("Idempotency-Key", idempotencyKey, 128);
        require("operationName", operationName, 128);
        require("payloadHash", payloadHash, 64);
        return unitOfWork.required(() -> {
            try {
                jdbc.update("""
                        INSERT INTO bc_command_idempotency
                        (tenant_id,idempotency_key,operation_name,payload_hash,created_at)
                        VALUES (?,?,?,?,?)
                        """, tenantId, idempotencyKey, operationName, payloadHash,
                        Timestamp.from(clock.instant()));
            } catch (DuplicateKeyException replay) {
                RecordedCommand recorded = recorded(tenantId, idempotencyKey);
                verifyReplay(recorded, operationName, payloadHash);
                if (recorded.resultPayload() == null) {
                    throw new IllegalStateException("idempotent result is missing");
                }
                return deserialize(recorded.resultPayload(), resultType);
            }
            T result = command.get();
            if (result == null) throw new IllegalStateException("idempotent command returned no result");
            int changed = jdbc.update("""
                    UPDATE bc_command_idempotency SET result_payload=?
                    WHERE tenant_id=? AND idempotency_key=?
                    """, serialize(result), tenantId, idempotencyKey);
            if (changed != 1) throw new IllegalStateException("idempotent result record was lost");
            return result;
        });
    }

    private RecordedCommand recorded(String tenantId, String idempotencyKey) {
        List<RecordedCommand> existing = jdbc.query("""
                SELECT operation_name,payload_hash,result_payload FROM bc_command_idempotency
                WHERE tenant_id=? AND idempotency_key=?
                """, (rs, row) -> new RecordedCommand(rs.getString("operation_name"),
                rs.getString("payload_hash"), rs.getString("result_payload")), tenantId, idempotencyKey);
        if (existing.isEmpty()) throw new IllegalStateException("idempotent winner is not visible");
        return existing.getFirst();
    }

    private static void verifyReplay(RecordedCommand recorded, String operationName, String payloadHash) {
        if (!recorded.operationName().equals(operationName) || !recorded.payloadHash().equals(payloadHash)) {
            throw new BenefitApplicationException(BenefitErrorCode.IDEMPOTENCY_PAYLOAD_CONFLICT,
                    "Idempotency-Key was already used with another operation or payload");
        }
    }

    private String serialize(Object result) {
        try {
            return json.writeValueAsString(result);
        } catch (Exception failure) {
            throw new IllegalStateException("idempotent result cannot be serialized", failure);
        }
    }

    private <T> T deserialize(String result, Class<T> resultType) {
        try {
            return json.readValue(result, resultType);
        } catch (Exception failure) {
            throw new IllegalStateException("idempotent result cannot be deserialized", failure);
        }
    }

    @Override
    public void execute(String tenantId, String idempotencyKey, String operationName,
                        String payloadHash, Runnable command) {
        require("tenantId", tenantId, 64);
        require("Idempotency-Key", idempotencyKey, 128);
        require("operationName", operationName, 128);
        require("payloadHash", payloadHash, 64);
        unitOfWork.required(() -> {
            try {
                jdbc.update("""
                        INSERT INTO bc_command_idempotency
                        (tenant_id,idempotency_key,operation_name,payload_hash,created_at)
                        VALUES (?,?,?,?,?)
                        """, tenantId, idempotencyKey, operationName, payloadHash,
                        Timestamp.from(clock.instant()));
            } catch (DuplicateKeyException replay) {
                verifyReplay(recorded(tenantId, idempotencyKey), operationName, payloadHash);
                return null;
            }
            command.run();
            return null;
        });
    }

    private static void require(String name, String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(name + " is required and must be at most " + maxLength + " characters");
        }
    }

    private record RecordedCommand(String operationName, String payloadHash, String resultPayload) {}
}
