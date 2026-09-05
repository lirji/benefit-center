package com.lrj.benefit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.port.in.ExecuteFulfillmentUseCase;
import com.lrj.benefit.application.port.in.ExecuteRemediationUseCase;
import com.lrj.benefit.application.port.out.ChannelAdapter;
import com.lrj.benefit.contract.*;
import com.lrj.benefit.domain.model.AdapterCapabilities;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benefit_slice1;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "benefit.security.dev-mode=true",
        "benefit.worker.enabled=false",
        "benefit.outbox.enabled=false",
        "benefit.cache.redis-enabled=false"
})
@AutoConfigureMockMvc
@Import(Slice1EndToEndTest.AdapterConfig.class)
class Slice1EndToEndTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecuteFulfillmentUseCase fulfillment;
    @Autowired ExecuteRemediationUseCase remediation;

    @Test
    void templateSwitchKeepsOldExpiryAndInvalidatesCurrentGeneration() throws Exception {
        String tenant = "T-TEMPLATE";
        seedTenant(tenant);
        seedTemplate(tenant, "COUPON-7D", "COUPON", null, null, 7, 5L, 6L);
        seedRouteAndQuota(tenant, "COUPON-7D", "Q-TEMPLATE", 10);

        accept(tenant, "REQ-OLD", "user-old", "COUPON-7D", BenefitType.COUPON, null, null)
                .andExpect(status().isAccepted());

        Map<String, Object> paused = Map.ofEntries(
                Map.entry("skuId", "COUPON-7D"),
                Map.entry("benefitType", "COUPON"),
                Map.entry("status", "PAUSED"),
                Map.entry("validityType", "RELATIVE"),
                Map.entry("relativeDays", 30),
                Map.entry("expectedVersion", 0));
        mvc.perform(put("/admin/v1/skus/{skuId}", "COUPON-7D")
                        .header("X-Tenant-Id", tenant).header("Idempotency-Key", "PAUSE-COUPON-7D")
                        .contentType("application/json")
                        .content(json.writeValueAsString(paused)))
                .andExpect(status().isNoContent());
        mvc.perform(put("/admin/v1/skus/{skuId}", "COUPON-7D")
                        .header("X-Tenant-Id", tenant).header("Idempotency-Key", "PAUSE-COUPON-7D")
                        .contentType("application/json").content(json.writeValueAsString(paused)))
                .andExpect(status().isNoContent());
        Map<String, Object> conflictingReplay = new java.util.HashMap<>(paused);
        conflictingReplay.put("relativeDays", 31);
        mvc.perform(put("/admin/v1/skus/{skuId}", "COUPON-7D")
                        .header("X-Tenant-Id", tenant).header("Idempotency-Key", "PAUSE-COUPON-7D")
                        .contentType("application/json").content(json.writeValueAsString(conflictingReplay)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='SKU_TEMPLATE_CHANGED'
                """, Integer.class, tenant)).isEqualTo(1);
        Map<String, Object> preservedLimits = jdbc.queryForMap("""
                SELECT user_limit_per_day,user_limit_total FROM bc_benefit_sku
                WHERE tenant_id=? AND sku_id='COUPON-7D'
                """, tenant);
        assertThat(preservedLimits.get("user_limit_per_day")).isEqualTo(5L);
        assertThat(preservedLimits.get("user_limit_total")).isEqualTo(6L);

        accept(tenant, "REQ-PAUSED", "user-new", "COUPON-7D", BenefitType.COUPON, null, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SKU_NOT_ACTIVE"));

        assertThat(fulfillment.runBatch(tenant, 10, "slice1-worker").succeeded()).isEqualTo(1);
        String walletBody = mvc.perform(get("/admin/v1/wallets/{subjectRef}/entries", "user-old")
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].skuVersion").value(0))
                .andReturn().getResponse().getContentAsString();
        Instant expiresAt = Instant.parse(json.readTree(walletBody).get(0).path("expiresAt").asText());
        assertThat(expiresAt).isAfter(Instant.now().plusSeconds(6 * 86_400L));
        assertThat(expiresAt).isBefore(Instant.now().plusSeconds(8 * 86_400L));
    }

    @Test
    void concurrentRequestsCannotExceedPerUserDailyLimit() throws Exception {
        String tenant = "T-LIMIT";
        seedTenant(tenant);
        seedTemplate(tenant, "LIMITED", "COUPON", null, null, 3, 1L, 1L);
        seedRouteAndQuota(tenant, "LIMITED", "Q-LIMIT", 20);

        int concurrency = 8;
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(concurrency)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < concurrency; index++) {
                int request = index;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return accept(tenant, "REQ-LIMIT-" + request, "same-user", "LIMITED",
                            BenefitType.COUPON, null, null).andReturn().getResponse().getStatus();
                }));
            }
            ready.await();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) statuses.add(future.get());
            assertThat(statuses.stream().filter(value -> value == 202).count()).isEqualTo(1);
            assertThat(statuses.stream().filter(value -> value == 409).count()).isEqualTo(concurrency - 1);
        }

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_award_order WHERE tenant_id=?
                """, Integer.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT MAX(reserved_count+issued_count) FROM bc_user_limit_counter WHERE tenant_id=?
                """, Long.class, tenant)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                SELECT reserved FROM bc_inventory_account WHERE tenant_id=? AND account_id='Q-LIMIT'
                """, Long.class, tenant)).isEqualTo(1L);
    }

    @Test
    void cashIssuanceCreatesWalletAndReversalAppendsOppositeLedger() throws Exception {
        String tenant = "T-CASH";
        seedTenant(tenant);
        seedTemplate(tenant, "CASH-100", "CASH", 100L, "CNY", 30, 2L, 2L);
        seedRouteAndQuota(tenant, "CASH-100", "Q-CASH", 10);

        String response = accept(tenant, "REQ-CASH", "cash-user", "CASH-100",
                BenefitType.CASH, 100L, "CNY")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String orderNo = json.readTree(response).path("awardOrderNo").asText();
        assertThat(fulfillment.runBatch(tenant, 10, "cash-issue-worker").succeeded()).isEqualTo(1);

        mvc.perform(get("/admin/v1/wallets/{subjectRef}", "cash-user").header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEntries").value(1))
                .andExpect(jsonPath("$.cashBalances[0].balanceMinor").value(100));
        String itemNo = jdbc.queryForObject("""
                SELECT item_no FROM bc_award_item WHERE tenant_id=? AND order_no=?
                """, String.class, tenant, orderNo);
        String operationNo = jdbc.queryForObject("""
                SELECT operation_no FROM bc_fulfillment_operation
                WHERE tenant_id=? AND item_no=? AND status='SUCCEEDED'
                """, String.class, tenant, itemNo);
        var approved = remediation.accept(tenant, new RemediationCommand("CMD-CASH-REVERSE",
                RemediationAction.REVERSE, itemNo, operationNo, "customer refund", "APPROVAL-CASH"));
        remediation.execute(tenant, approved.remediationNo(), "cash-reverse-command");
        assertThat(fulfillment.runBatch(tenant, 10, "cash-reverse-worker").succeeded()).isEqualTo(1);

        mvc.perform(get("/admin/v1/wallets/{subjectRef}", "cash-user").header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cashBalances[0].balanceMinor").value(0));
        mvc.perform(get("/admin/v1/wallets/{subjectRef}/entries", "cash-user")
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("REVERSED"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_wallet_balance_ledger WHERE tenant_id=?
                """, Integer.class, tenant)).isEqualTo(2);
    }

    private org.springframework.test.web.servlet.ResultActions accept(
            String tenant, String requestId, String subject, String skuId,
            BenefitType type, Long amount, String currency) throws Exception {
        AwardIntent intent = new AwardIntent("1.0", "slice1-test", requestId, null, subject, null,
                PartialPolicy.BEST_EFFORT, List.of(new AwardItemIntent("item-" + requestId, skuId,
                type, amount, currency, 1, Map.of())), Map.of());
        return mvc.perform(post("/openapi/v1/award-orders").header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", requestId).contentType("application/json")
                .content(json.writeValueAsString(intent)));
    }

    private void seedTenant(String tenant) {
        jdbc.update("INSERT INTO bc_tenant_config(tenant_id,home_cell,status,version) VALUES (?,'cell-0','ENABLED',0)",
                tenant);
    }

    private void seedTemplate(String tenant, String skuId, String type, Long amount, String currency,
                              int relativeDays, Long dailyLimit, Long totalLimit) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO bc_benefit_sku
                (tenant_id,sku_id,benefit_type,currency,face_value_minor,status,metadata_json,version,
                 validity_type,valid_from,valid_to,relative_days,usable_weekdays,daily_quota,
                 user_limit_per_day,user_limit_total,equivalent_sku_id)
                VALUES (?,?,?,?,?,'ACTIVE',NULL,0,'RELATIVE',NULL,NULL,?,NULL,NULL,?,?,NULL)
                """, tenant, skuId, type, currency, amount, relativeDays, dailyLimit, totalLimit);
        jdbc.update("""
                INSERT INTO bc_sku_template_version
                (tenant_id,sku_id,version,benefit_type,currency,face_value_minor,status,validity_type,
                 valid_from,valid_to,relative_days,usable_weekdays,daily_quota,user_limit_per_day,
                 user_limit_total,equivalent_sku_id,created_at)
                VALUES (?,?,0,?,?,?,'ACTIVE','RELATIVE',NULL,NULL,?,NULL,NULL,?,?,NULL,?)
                """, tenant, skuId, type, currency, amount, relativeDays, dailyLimit, totalLimit, now);
    }

    private void seedRouteAndQuota(String tenant, String skuId, String accountId, long available) {
        jdbc.update("""
                INSERT INTO bc_channel_route
                (tenant_id,route_id,sku_id,priority_no,channel_code,owner_type,fallback_route_id,
                 reserve_mode,enabled,config_ref,version)
                VALUES (?,CONCAT('R-',?),?,1,'SLICE1_OK','CENTER_QUOTA',NULL,'LAZY',TRUE,NULL,0)
                """, tenant, skuId, skuId);
        jdbc.update("""
                INSERT INTO bc_inventory_account
                (tenant_id,account_id,sku_id,owner_type,owner_id,available,reserved,issued,version,snapshot_at)
                VALUES (?,?,?,'CENTER_QUOTA','platform',?,0,0,0,NULL)
                """, tenant, accountId, skuId, available);
    }

    @TestConfiguration
    static class AdapterConfig {
        @Bean
        ChannelAdapter slice1SuccessAdapter() {
            return new ChannelAdapter() {
                @Override public String channelCode() { return "SLICE1_OK"; }
                @Override public AdapterCapabilities capabilities() {
                    return new AdapterCapabilities(true, true, true, false);
                }
                @Override public ChannelResult issue(ChannelCommand command) {
                    return new ChannelResult(ChannelResult.ResultType.SUCCEEDED,
                            "provider-" + command.operationNo(), null, null);
                }
                @Override public ChannelResult query(ChannelCommand command) {
                    return issue(command);
                }
                @Override public ChannelResult reverse(ChannelCommand command) {
                    return new ChannelResult(ChannelResult.ResultType.SUCCEEDED,
                            "reversed-" + command.operationNo(), null, null);
                }
            };
        }
    }
}
