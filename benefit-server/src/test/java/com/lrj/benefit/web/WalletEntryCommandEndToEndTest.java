package com.lrj.benefit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benefit_wallet_commands;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "benefit.security.dev-mode=true",
        "benefit.worker.enabled=false",
        "benefit.outbox.enabled=false",
        "benefit.cache.redis-enabled=false"
})
@AutoConfigureMockMvc
class WalletEntryCommandEndToEndTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test
    void redeemUsesIssuedSnapshotAndReplaysFirstSynchronousResult() throws Exception {
        String tenant = "T-WALLET-REDEEM";
        seedWallet(tenant, "WE-REDEEM", "CASH_BALANCE", "UNUSED", 0,
                Instant.now().plusSeconds(3600), 100L, "CNY");
        // 当前模板已 PAUSED 且面额改变，核销仍只能读取 WalletEntry 的 100 分快照。
        jdbc.update("""
                INSERT INTO bc_benefit_sku
                (tenant_id,sku_id,benefit_type,currency,face_value_minor,status,metadata_json,version)
                VALUES (?,'SKU-WALLET','CASH','CNY',999,'PAUSED',NULL,9)
                """, tenant);

        String command = "{\"reason\":\"checkout\",\"merchantRef\":\"M-1\",\"expectedVersion\":0}";
        mvc.perform(walletPost(tenant, "WE-REDEEM", "redeem", "redeem-key", command))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.entryId").value("WE-REDEEM"))
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.version").value(1));

        assertThat(jdbc.queryForObject("""
                SELECT delta_minor FROM bc_wallet_balance_ledger
                WHERE tenant_id=? AND wallet_entry_id=? AND entry_type='REDEEM'
                """, Long.class, tenant, "WE-REDEEM")).isEqualTo(-100L);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='FULFILLMENT_WALLET'
                """, Integer.class, tenant)).isEqualTo(1);
        String payload = jdbc.queryForObject("""
                SELECT payload FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='FULFILLMENT_WALLET'
                """, String.class, tenant);
        JsonNode fact = json.readTree(payload).path("payload");
        assertThat(fact.path("walletEntryId").asText()).isEqualTo("WE-REDEEM");
        assertThat(fact.path("skuVersion").asLong()).isEqualTo(7L);
        assertThat(fact.path("amountMinor").asLong()).isEqualTo(100L);
        assertThat(fact.path("currency").asText()).isEqualTo("CNY");
        assertThat(fact.path("status").asText()).isEqualTo("USED");
        assertThat(fact.path("entryType").asText()).isEqualTo("REDEEM");

        mvc.perform(walletPost(tenant, "WE-REDEEM", "redeem", "redeem-key", command))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(countLedger(tenant, "REDEEM")).isEqualTo(1);
        assertThat(countWalletFacts(tenant)).isEqualTo(1);

        mvc.perform(walletPost(tenant, "WE-REDEEM", "redeem", "redeem-key",
                        "{\"reason\":\"different\",\"merchantRef\":\"M-1\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        mvc.perform(walletPost(tenant, "WE-REDEEM", "redeem", "redeem-again", "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_ALREADY_USED"));

        mvc.perform(get("/admin/v1/wallets/{subjectRef}/entries", "USER-WALLET")
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1));
    }

    @Test
    void freezeRedeemRefundUseDistinctStateFactsAndCashLedgerEntries() throws Exception {
        String tenant = "T-WALLET-CHAIN";
        seedWallet(tenant, "WE-CHAIN", "CASH_BALANCE", "UNUSED", 0,
                Instant.now().plusSeconds(3600), 250L, "CNY");

        mvc.perform(walletPost(tenant, "WE-CHAIN", "freeze", "freeze-key",
                        "{\"reason\":\"risk hold\",\"expectedVersion\":0}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(countLedger(tenant, "FREEZE")).isZero();

        mvc.perform(walletPost(tenant, "WE-CHAIN", "redeem", "redeem-chain-key",
                        "{\"merchantRef\":\"STORE-1\",\"expectedVersion\":1}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.version").value(2));
        mvc.perform(walletPost(tenant, "WE-CHAIN", "freeze", "freeze-after-used", "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_ALREADY_USED"))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.message").isString());
        // 到期只阻止尚未消费的资产；已经 USED 的资产仍可按原消费退款。
        jdbc.update("UPDATE bc_wallet_entry SET expires_at=? WHERE tenant_id=? AND entry_id=?",
                Timestamp.from(Instant.now().minusSeconds(1)), tenant, "WE-CHAIN");
        mvc.perform(walletPost(tenant, "WE-CHAIN", "refund", "refund-key",
                        "{\"reason\":\"returned\",\"expectedVersion\":2}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REVERSED"))
                .andExpect(jsonPath("$.version").value(3));

        assertThat(countLedger(tenant, "REDEEM")).isEqualTo(1);
        assertThat(countLedger(tenant, "REFUND")).isEqualTo(1);
        assertThat(countLedger(tenant, "REVERSAL")).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT delta_minor FROM bc_wallet_balance_ledger
                WHERE tenant_id=? AND entry_type='REFUND'
                """, Long.class, tenant)).isEqualTo(250L);
        assertThat(countWalletFacts(tenant)).isEqualTo(3);

        // 条目已继续迁移到 REVERSED，旧 freeze 键仍精确重放首次的 FROZEN/version=1。
        mvc.perform(walletPost(tenant, "WE-CHAIN", "freeze", "freeze-key",
                        "{\"reason\":\"risk hold\",\"expectedVersion\":0}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.version").value(1));
        assertThat(countWalletFacts(tenant)).isEqualTo(3);

        mvc.perform(walletPost(tenant, "WE-CHAIN", "refund", "refund-again", "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_ILLEGAL_TRANSITION"));
    }

    @Test
    void rejectsExpiredMissingAndWrongVersionWhileNonCashWritesNoBalanceLedger() throws Exception {
        String tenant = "T-WALLET-ERRORS";
        seedWallet(tenant, "WE-EXPIRED", "COUPON", "UNUSED", 0,
                Instant.now().minusSeconds(1), null, null);
        seedWallet(tenant, "WE-VERSION", "COUPON", "UNUSED", 4,
                Instant.now().plusSeconds(3600), null, null);

        mvc.perform(walletPost(tenant, "WE-EXPIRED", "redeem", "expired-key", "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_EXPIRED"));
        assertThat(walletStatus(tenant, "WE-EXPIRED")).isEqualTo("UNUSED");

        mvc.perform(walletPost(tenant, "WE-VERSION", "freeze", "version-key",
                        "{\"expectedVersion\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WALLET_VERSION_CONFLICT"));
        mvc.perform(walletPost(tenant, "WE-MISSING", "redeem", "missing-key", "{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_ENTRY_NOT_FOUND"));
        mvc.perform(post("/openapi/v1/wallet-entries/{entryId}:redeem", "WE-VERSION")
                        .header("X-Tenant-Id", tenant)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(walletPost(tenant, "WE-VERSION", "redeem", "coupon-redeem",
                        "{\"expectedVersion\":4}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("USED"))
                .andExpect(jsonPath("$.version").value(5));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_wallet_balance_ledger WHERE tenant_id=?
                """, Integer.class, tenant)).isZero();
        String payload = jdbc.queryForObject("""
                SELECT payload FROM bc_outbox_event
                WHERE tenant_id=? AND event_type='FULFILLMENT_WALLET'
                """, String.class, tenant);
        JsonNode fact = json.readTree(payload).path("payload");
        assertThat(fact.has("amountMinor")).isFalse();
        assertThat(fact.has("currency")).isFalse();
    }

    @Test
    void concurrentRedeemAllowsOnlyOneCasWinner() throws Exception {
        String tenant = "T-WALLET-RACE";
        seedWallet(tenant, "WE-RACE", "CASH_BALANCE", "UNUSED", 0,
                Instant.now().plusSeconds(3600), 80L, "CNY");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            List<Future<Response>> requests = new ArrayList<>();
            for (int index = 0; index < 2; index++) {
                String key = "race-key-" + index;
                requests.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    var response = mvc.perform(walletPost(tenant, "WE-RACE", "redeem", key, "{}"))
                            .andReturn().getResponse();
                    return new Response(response.getStatus(), response.getContentAsString());
                }));
            }
            ready.await();
            start.countDown();
            List<Response> results = new ArrayList<>();
            for (Future<Response> request : requests) results.add(request.get());
            assertThat(results.stream().filter(result -> result.status() == 202).count()).isEqualTo(1);
            assertThat(results.stream().filter(result -> result.status() == 409).count()).isEqualTo(1);
            Response conflict = results.stream().filter(result -> result.status() == 409).findFirst().orElseThrow();
            assertThat(json.readTree(conflict.body()).path("code").asText()).isEqualTo("WALLET_ALREADY_USED");
        }

        assertThat(walletStatus(tenant, "WE-RACE")).isEqualTo("USED");
        assertThat(walletVersion(tenant, "WE-RACE")).isEqualTo(1L);
        assertThat(countLedger(tenant, "REDEEM")).isEqualTo(1);
        assertThat(countWalletFacts(tenant)).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder walletPost(
            String tenant, String entryId, String action, String key, String body) {
        return post("/openapi/v1/wallet-entries/{entryId}:" + action, entryId)
                .header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(body);
    }

    private void seedWallet(String tenant, String entryId, String assetType, String status,
                            long version, Instant expiresAt, Long amount, String currency) {
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO bc_wallet_entry
                (tenant_id,entry_id,subject_ref,sku_id,sku_version,award_order_no,item_no,asset_type,
                 status,version,expires_at,face_value_minor,currency,created_at,updated_at)
                VALUES (?,?,'USER-WALLET','SKU-WALLET',7,CONCAT('ORD-',?),CONCAT('ITEM-',?),
                        ?,?,?,?,?,?,?,?)
                """, tenant, entryId, entryId, entryId, assetType, status, version,
                Timestamp.from(expiresAt), amount, currency, Timestamp.from(now), Timestamp.from(now));
    }

    private int countLedger(String tenant, String entryType) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_wallet_balance_ledger WHERE tenant_id=? AND entry_type=?
                """, Integer.class, tenant, entryType);
    }

    private int countWalletFacts(String tenant) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM bc_outbox_event WHERE tenant_id=? AND event_type='FULFILLMENT_WALLET'
                """, Integer.class, tenant);
    }

    private String walletStatus(String tenant, String entryId) {
        return jdbc.queryForObject("""
                SELECT status FROM bc_wallet_entry WHERE tenant_id=? AND entry_id=?
                """, String.class, tenant, entryId);
    }

    private long walletVersion(String tenant, String entryId) {
        return jdbc.queryForObject("""
                SELECT version FROM bc_wallet_entry WHERE tenant_id=? AND entry_id=?
                """, Long.class, tenant, entryId);
    }

    private record Response(int status, String body) { }
}
