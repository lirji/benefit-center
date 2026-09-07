package com.lrj.benefit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.adapters.messaging.KafkaSkuGoLiveActionConsumer;
import com.lrj.benefit.application.port.out.SkuTemplateCache;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Slice 4a SKU 首次上线审批的 HTTP、幂等与 CAS 验收测试。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benefit_slice4a;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "benefit.security.dev-mode=true",
        "benefit.worker.enabled=false",
        "benefit.outbox.enabled=false",
        "benefit.cache.redis-enabled=false",
        "workflow.kafka.enabled=true",
        "workflow.kafka.configuration-guard-enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@AutoConfigureMockMvc
class Slice4aSkuApprovalEndToEndTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired KafkaSkuGoLiveActionConsumer workflowConsumer;
    @Autowired SkuTemplateCache templateCache;

    @Test
    void draftSubmissionIsAcceptedAndSameKeyReplaysWithoutAnotherStart() throws Exception {
        String tenant = "T-4A-SUBMIT";
        seedDraft(tenant, "SKU-4A-SUBMIT", 0);

        String body = json.writeValueAsString(Map.of("expectedVersion", 0));
        submit(tenant, "SKU-4A-SUBMIT", "submit-key", body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.skuId").value("SKU-4A-SUBMIT"))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.version").value(1));
        submit(tenant, "SKU-4A-SUBMIT", "submit-key", body)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.version").value(1));

        Map<String, Object> stored = jdbc.queryForMap("""
                SELECT status,version FROM bc_benefit_sku WHERE tenant_id=? AND sku_id=?
                """, tenant, "SKU-4A-SUBMIT");
        assertThat(stored.get("status")).isEqualTo("PENDING_APPROVAL");
        assertThat(stored.get("version")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='workflow.command.start.v1'
                """, Integer.class, tenant)).isEqualTo(1);
        String raw = jdbc.queryForObject("""
                SELECT payload FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='workflow.command.start.v1'
                """, String.class, tenant);
        JsonNode envelope = json.readTree(raw);
        assertThat(envelope.path("source").asText()).isEqualTo("benefit-center");
        assertThat(envelope.path("payload").path("processDefinitionKey").asText()).isEqualTo("benefitSkuGoLive");
        assertThat(envelope.path("payload").path("businessKey").asText()).isEqualTo("SKU-4A-SUBMIT");
        assertThat(envelope.path("payload").path("idempotencyKey").asText())
                .isEqualTo("T-4A-SUBMIT|SKU-4A-SUBMIT|0");
        assertThat(envelope.path("payload").path("variables").path("skuVersion").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT aggregate_id FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='workflow.command.start.v1'
                """, String.class, tenant)).isEqualTo("T-4A-SUBMIT|benefitSkuGoLive|SKU-4A-SUBMIT");
        mvc.perform(get("/admin/v1/skus").header("X-Tenant-Id", tenant)
                        .param("status", "PENDING_APPROVAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].approvalProcessDefinitionKey").value("benefitSkuGoLive"))
                .andExpect(jsonPath("$[0].approvalBusinessKey").value("SKU-4A-SUBMIT"));

        submit(tenant, "SKU-4A-SUBMIT", "submit-key",
                json.writeValueAsString(Map.of("expectedVersion", 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
    }

    @Test
    void putCannotBypassApprovalAndPendingTemplateIsLocked() throws Exception {
        String tenant = "T-4A-PUT";
        seedDraft(tenant, "SKU-4A-PUT", 0);

        putSku(tenant, "SKU-4A-PUT", "illegal-active", skuBody("SKU-4A-PUT", "ACTIVE", true, 0, 7))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_ILLEGAL_TRANSITION"));

        submit(tenant, "SKU-4A-PUT", "submit-put", "{\"expectedVersion\":0}")
                .andExpect(status().isAccepted());
        putSku(tenant, "SKU-4A-PUT", "locked-edit",
                skuBody("SKU-4A-PUT", "PENDING_APPROVAL", false, 1, 30))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_APPROVAL_LOCKED"));
    }

    @Test
    void submitFailuresExposeStableSkuCodes() throws Exception {
        String tenant = "T-4A-CODES";
        seedDraft(tenant, "SKU-4A-VERSION", 3);
        seedSku(tenant, "SKU-4A-ACTIVE", "ACTIVE", 1);
        seedSku(tenant, "SKU-4A-PENDING", "PENDING_APPROVAL", 1);

        submit(tenant, "SKU-4A-VERSION", "bad-version", "{\"expectedVersion\":2}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_VERSION_CONFLICT"));
        submit(tenant, "SKU-4A-ACTIVE", "not-draft", "{\"expectedVersion\":1}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_NOT_DRAFT"));
        submit(tenant, "SKU-4A-PENDING", "in-flight", "{\"expectedVersion\":0}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_SUBMIT_IN_FLIGHT"));
        submit(tenant, "SKU-4A-VERSION", "missing-version", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INTENT"));
    }

    @Test
    void pendingApprovalCanRetryStartWithoutCreatingAnotherWorkflowCycle() throws Exception {
        String tenant = "T-4A-RETRY";
        String skuId = "SKU-4A-RETRY";
        seedSku(tenant, skuId, "PENDING_APPROVAL", 1);

        mvc.perform(post("/admin/v1/skus/{skuId}:retry-approval", skuId)
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", "retry-command-1")
                        .header("X-Operator", "slice4a-tester")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.version").value(1));

        String payload = jdbc.queryForObject("""
                SELECT payload FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='workflow.command.start.v1'
                """, String.class, tenant);
        assertThat(json.readTree(payload).path("payload").path("idempotencyKey").asText())
                .isEqualTo(tenant + '|' + skuId + "|0");

        // HTTP 幂等重放不能再插入第二条 outbox；workflow 幂等键则防止不同重提命令重复起实例。
        mvc.perform(post("/admin/v1/skus/{skuId}:retry-approval", skuId)
                        .header("X-Tenant-Id", tenant)
                        .header("Idempotency-Key", "retry-command-1")
                        .header("X-Operator", "slice4a-tester")
                        .contentType("application/json")
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='workflow.command.start.v1'
                """, Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void retryRequiresPendingApprovalAndCurrentVersion() throws Exception {
        String tenant = "T-4A-RETRY-CODES";
        seedDraft(tenant, "SKU-4A-RETRY-DRAFT", 0);
        seedSku(tenant, "SKU-4A-RETRY-VERSION", "PENDING_APPROVAL", 2);

        mvc.perform(post("/admin/v1/skus/{skuId}:retry-approval", "SKU-4A-RETRY-DRAFT")
                        .header("X-Tenant-Id", tenant).header("Idempotency-Key", "retry-draft")
                        .contentType("application/json").content("{\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_APPROVAL_NOT_PENDING"));
        mvc.perform(post("/admin/v1/skus/{skuId}:retry-approval", "SKU-4A-RETRY-VERSION")
                        .header("X-Tenant-Id", tenant).header("Idempotency-Key", "retry-version")
                        .contentType("application/json").content("{\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_VERSION_CONFLICT"));
    }

    @Test
    void pendingApprovalCanWithdrawAndThenSubmitAgain() throws Exception {
        String tenant = "T-4A-WITHDRAW";
        String skuId = "SKU-4A-WITHDRAW";
        seedSku(tenant, skuId, "PENDING_APPROVAL", 1);

        withdraw(tenant, skuId, "withdraw-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(2));
        assertThat(jdbc.queryForMap("""
                SELECT status,version FROM bc_benefit_sku WHERE tenant_id=? AND sku_id=?
                """, tenant, skuId)).containsEntry("status", "DRAFT").containsEntry("version", 2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_sku_template_version
                WHERE tenant_id=? AND sku_id=? AND version=2 AND status='DRAFT'
                """, Integer.class, tenant, skuId)).isEqualTo(1);

        // 退回后使用新版本再次提交，才创建下一审批周期；withdraw 本身不产生 start。
        submit(tenant, skuId, "submit-after-withdraw", "{\"expectedVersion\":2}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version").value(3));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='workflow.command.start.v1'
                """, Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void withdrawRequiresPendingApprovalAndCurrentVersion() throws Exception {
        String tenant = "T-4A-WITHDRAW-CODES";
        seedDraft(tenant, "SKU-4A-WITHDRAW-DRAFT", 0);
        seedSku(tenant, "SKU-4A-WITHDRAW-ACTIVE", "ACTIVE", 1);
        seedSku(tenant, "SKU-4A-WITHDRAW-VERSION", "PENDING_APPROVAL", 2);

        withdraw(tenant, "SKU-4A-WITHDRAW-DRAFT", "withdraw-draft", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_APPROVAL_NOT_PENDING"));
        withdraw(tenant, "SKU-4A-WITHDRAW-ACTIVE", "withdraw-active", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_APPROVAL_NOT_PENDING"));
        withdraw(tenant, "SKU-4A-WITHDRAW-VERSION", "withdraw-version", 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_VERSION_CONFLICT"));
    }

    @Test
    void concurrentSubmissionsOfSameVersionCreateOnePendingAndOneStart() throws Exception {
        String tenant = "T-4A-RACE";
        seedDraft(tenant, "SKU-4A-RACE", 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String key = "race-" + i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    var response = submit(tenant, "SKU-4A-RACE", key, "{\"expectedVersion\":0}")
                            .andReturn().getResponse();
                    return new Response(response.getStatus(), json.readTree(response.getContentAsString())
                            .path("code").asText(null));
                }));
            }
            ready.await();
            start.countDown();
            List<Response> responses = List.of(futures.get(0).get(), futures.get(1).get());
            assertThat(responses.stream().filter(it -> it.status() == 202).count()).isEqualTo(1);
            assertThat(responses.stream().filter(it -> it.status() == 409).count()).isEqualTo(1);
            assertThat(responses.stream().filter(it -> "SKU_SUBMIT_IN_FLIGHT".equals(it.code())).count())
                    .isEqualTo(1);
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='workflow.command.start.v1'
                """, Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void legacyEnabledCannotActivateOnInsertOrDraftUpdate() throws Exception {
        String tenant = "T-4A-LEGACY";
        seedTenant(tenant);
        putSku(tenant, "SKU-4A-INSERT", "legacy-insert",
                skuBody("SKU-4A-INSERT", null, true, null, 7)).andExpect(status().isNoContent());
        assertStatus(tenant, "SKU-4A-INSERT", "DRAFT");

        putSku(tenant, "SKU-4A-INSERT", "legacy-update",
                skuBody("SKU-4A-INSERT", null, true, 0, 8)).andExpect(status().isNoContent());
        assertStatus(tenant, "SKU-4A-INSERT", "DRAFT");
    }

    @Test
    void workflowApproveInvalidatesL1AndActionReplayHasNoSecondSideEffect() throws Exception {
        String tenant = "T-4A-APPROVE";
        String skuId = "SKU-4A-APPROVE";
        seedDraft(tenant, skuId, 0);
        assertThat(templateCache.findCurrent(tenant, skuId).orElseThrow().status().name()).isEqualTo("DRAFT");
        submit(tenant, skuId, "approve-submit", "{\"expectedVersion\":0}")
                .andExpect(status().isAccepted());

        String actionPayload = workflowActionEnvelope(tenant, "evt-approve-1", "action-approve-1",
                skuId, "SKU_GO_LIVE_APPROVE");
        workflowConsumer.consume(record(actionPayload));
        assertStatus(tenant, skuId, "ACTIVE");
        assertThat(templateCache.findCurrent(tenant, skuId).orElseThrow().status().name()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("""
                SELECT version FROM bc_benefit_sku WHERE tenant_id=? AND sku_id=?
                """, Long.class, tenant, skuId)).isEqualTo(2L);

        workflowConsumer.consume(record(workflowActionEnvelope(tenant, "evt-approve-replay",
                "action-approve-1", skuId, "SKU_GO_LIVE_APPROVE")));
        assertThat(jdbc.queryForObject("""
                SELECT version FROM bc_benefit_sku WHERE tenant_id=? AND sku_id=?
                """, Long.class, tenant, skuId)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='workflow.action.applied.v1'
                """, Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void workflowRejectReturnsPendingTemplateToDraft() throws Exception {
        String tenant = "T-4A-REJECT";
        String skuId = "SKU-4A-REJECT";
        seedDraft(tenant, skuId, 0);
        submit(tenant, skuId, "reject-submit", "{\"expectedVersion\":0}")
                .andExpect(status().isAccepted());

        workflowConsumer.consume(record(workflowActionEnvelope(tenant, "evt-reject-1",
                "action-reject-1", skuId, "SKU_GO_LIVE_REJECT")));
        assertStatus(tenant, skuId, "DRAFT");
        String applied = jdbc.queryForObject("""
                SELECT payload FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='workflow.action.applied.v1'
                """, String.class, tenant);
        assertThat(json.readTree(applied).path("payload").path("status").asText()).isEqualTo("APPLIED");
    }

    @Test
    void workflowActionAgainstNonPendingSkuReturnsBusinessRejection() throws Exception {
        String tenant = "T-4A-BUSINESS-REJECT";
        String skuId = "SKU-4A-NOT-PENDING";
        seedSku(tenant, skuId, "ACTIVE", 4);

        workflowConsumer.consume(record(workflowActionEnvelope(tenant, "evt-business-reject",
                "action-business-reject", skuId, "SKU_GO_LIVE_APPROVE")));
        assertStatus(tenant, skuId, "ACTIVE");
        String applied = jdbc.queryForObject("""
                SELECT payload FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='workflow.action.applied.v1'
                """, String.class, tenant);
        assertThat(json.readTree(applied).path("payload").path("status").asText())
                .isEqualTo("REJECTED_BY_BUSINESS");
    }

    @Test
    void sharedWorkflowTopicIgnoresHisActions() throws Exception {
        String raw = """
                {"eventId":"evt-his","contractVersion":1,"eventType":"workflow.action.requested.v1",
                 "occurredAt":"2026-09-05T00:00:00Z","source":"workflow-server","tenantId":"his",
                 "correlationId":"action-his","causationId":null,
                 "payload":{"processInstanceId":"pi-his","taskId":"task-his","taskDefinitionKey":"pharmacistReview",
                 "processDefinitionKey":"hisRxReview","businessKey":"enc-1","actionId":"action-his",
                 "action":"RX_REVIEW_PASS","actor":null,"parameters":{}}}
                """;
        workflowConsumer.consume(record(raw));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_inbox_message WHERE tenant_id='his'
                """, Integer.class)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions submit(
            String tenant, String skuId, String key, String body) throws Exception {
        return mvc.perform(post("/admin/v1/skus/{skuId}:submit-for-approval", skuId)
                .header("X-Tenant-Id", tenant).header("Idempotency-Key", key)
                .header("X-Operator", "slice4a-tester")
                .contentType("application/json").content(body));
    }

    private org.springframework.test.web.servlet.ResultActions putSku(
            String tenant, String skuId, String key, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/admin/v1/skus/{skuId}", skuId)
                .header("X-Tenant-Id", tenant).header("Idempotency-Key", key)
                .contentType("application/json").content(json.writeValueAsString(body)));
    }

    private Map<String, Object> skuBody(String skuId, String status, boolean enabled,
                                        Integer expectedVersion, int relativeDays) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("skuId", skuId);
        body.put("benefitType", "COUPON");
        body.put("status", status);
        body.put("enabled", enabled);
        body.put("validityType", "RELATIVE");
        body.put("relativeDays", relativeDays);
        body.put("expectedVersion", expectedVersion);
        return body;
    }

    private void seedDraft(String tenant, String skuId, long version) {
        seedSku(tenant, skuId, "DRAFT", version);
    }

    private void seedSku(String tenant, String skuId, String status, long version) {
        seedTenant(tenant);
        jdbc.update("""
                INSERT INTO bc_benefit_sku
                (tenant_id,sku_id,benefit_type,currency,face_value_minor,status,metadata_json,version,
                 validity_type,valid_from,valid_to,relative_days,usable_weekdays,daily_quota,
                 user_limit_per_day,user_limit_total,equivalent_sku_id)
                VALUES (?,?,'COUPON',NULL,NULL,?,NULL,?,'RELATIVE',NULL,NULL,7,NULL,NULL,NULL,NULL,NULL)
                """, tenant, skuId, status, version);
        jdbc.update("""
                INSERT INTO bc_sku_template_version
                (tenant_id,sku_id,version,benefit_type,currency,face_value_minor,status,validity_type,
                 valid_from,valid_to,relative_days,usable_weekdays,daily_quota,user_limit_per_day,
                 user_limit_total,equivalent_sku_id,created_at)
                VALUES (?,?,?,'COUPON',NULL,NULL,?,'RELATIVE',NULL,NULL,7,NULL,NULL,NULL,NULL,NULL,?)
                """, tenant, skuId, version, status, java.sql.Timestamp.from(Instant.now()));
    }

    private void seedTenant(String tenant) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM bc_tenant_config WHERE tenant_id=?",
                Integer.class, tenant);
        if (count == 0) {
            jdbc.update("INSERT INTO bc_tenant_config(tenant_id,home_cell,status,version) VALUES (?,'cell-0','ENABLED',0)",
                    tenant);
        }
    }

    private void assertStatus(String tenant, String skuId, String status) {
        assertThat(jdbc.queryForObject("""
                SELECT status FROM bc_benefit_sku WHERE tenant_id=? AND sku_id=?
                """, String.class, tenant, skuId)).isEqualTo(status);
    }

    private org.springframework.test.web.servlet.ResultActions withdraw(
            String tenant, String skuId, String idempotencyKey, long expectedVersion) throws Exception {
        return mvc.perform(post("/admin/v1/skus/{skuId}:withdraw-approval", skuId)
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content("{\"expectedVersion\":" + expectedVersion + '}'));
    }

    private String workflowActionEnvelope(String tenant, String eventId, String actionId,
                                          String skuId, String action) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("processInstanceId", "pi-" + skuId);
        payload.put("taskId", "task-" + skuId);
        payload.put("taskDefinitionKey", "skuGoLiveReview");
        payload.put("processDefinitionKey", "benefitSkuGoLive");
        payload.put("businessKey", skuId);
        payload.put("actionId", actionId);
        payload.put("action", action);
        payload.put("actor", Map.of(
                "subjectId", "reviewer-sub",
                "username", "reviewer",
                "displayName", "SKU Reviewer"));
        payload.put("parameters", Map.of());
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("eventId", eventId);
        envelope.put("contractVersion", 1);
        envelope.put("eventType", "workflow.action.requested.v1");
        envelope.put("occurredAt", "2026-09-05T00:00:00Z");
        envelope.put("source", "workflow-server");
        envelope.put("tenantId", tenant);
        envelope.put("correlationId", actionId);
        envelope.put("causationId", null);
        envelope.put("payload", payload);
        return json.writeValueAsString(envelope);
    }

    private static ConsumerRecord<String, String> record(String raw) {
        return new ConsumerRecord<>("workflow.action.requested.v1", 0, 0L, null, raw);
    }

    private record Response(int status, String code) {}
}
