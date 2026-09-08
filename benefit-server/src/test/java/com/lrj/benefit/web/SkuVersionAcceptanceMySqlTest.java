package com.lrj.benefit.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.port.out.SkuAcceptanceRepository;
import com.lrj.benefit.application.port.out.SkuTemplateCache;
import com.lrj.benefit.contract.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 临时真实MySQL验证受理事务的版本锁；不连接外部数据库，也不执行任何发奖worker。 */
@Testcontainers
@SpringBootTest(properties = {
        "benefit.security.dev-mode=true", "benefit.worker.enabled=false", "benefit.outbox.enabled=false",
        "benefit.cache.redis-enabled=false", "benefit.cache.template-ttl=PT1H",
        "benefit.award-intent.consumer-enabled=false", "benefit.remediation.consumer-enabled=false",
        "benefit.remediation.auto-enabled=false", "workflow.kafka.enabled=false",
        "benefit.channel.real-enabled=false", "benefit.channel.http.enabled=false"
})
@AutoConfigureMockMvc
class SkuVersionAcceptanceMySqlTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("sku_version_acceptance").withUsername("benefit_test").withPassword("isolated_test")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withStartupTimeout(Duration.ofMinutes(5))
            .withConnectTimeoutSeconds(300)
            .withCommand("--performance-schema=ON", "--innodb-lock-wait-timeout=15");

    /** 动态地址只来自本类容器，覆盖环境中的共享库连接。 */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", MYSQL::getJdbcUrl);
        properties.add("spring.datasource.username", MYSQL::getUsername);
        properties.add("spring.datasource.password", MYSQL::getPassword);
        properties.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired SkuTemplateCache cache;
    @Autowired SkuAcceptanceRepository lockedSkus;
    @Autowired PlatformTransactionManager transactions;

    @Test
    void httpExpectedVersionZeroIsPersistedAndReservesExactlyOnce() throws Exception {
        String tenant = seed("T-VERSION-ZERO");
        var response = accept(tenant, "new", 0L).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.replay").value(false)).andReturn().getResponse();
        String order = json.readTree(response.getContentAsString()).path("awardOrderNo").asText();
        assertThat(jdbc.queryForObject("SELECT sku_version FROM bc_award_item WHERE tenant_id=? AND order_no=?",
                Long.class, tenant, order)).isZero();
        assertThat(jdbc.queryForObject("SELECT reserved FROM bc_inventory_account WHERE tenant_id=?",
                Long.class, tenant)).isEqualTo(1L);
        assertThat(count("bc_outbox_event", tenant)).isEqualTo(1);
    }

    @Test
    void legacyHttpWithoutVersionStillAcceptsCurrentTemplate() throws Exception {
        String tenant = seed("T-VERSION-LEGACY");
        jdbc.update("UPDATE bc_benefit_sku SET version=4 WHERE tenant_id=?", tenant);
        accept(tenant, "legacy", null).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT sku_version FROM bc_award_item WHERE tenant_id=?",
                Long.class, tenant)).isEqualTo(4L);
    }

    @Test
    void staleRealCacheCannotAcceptWrongVersionOrInactiveCurrentTemplate() throws Exception {
        String tenant = seed("T-VERSION-CACHE");
        assertThat(cache.findCurrent(tenant, "SKU").orElseThrow().version()).isZero();
        jdbc.update("UPDATE bc_benefit_sku SET version=1,status='PAUSED' WHERE tenant_id=?", tenant);
        assertThat(cache.findCurrent(tenant, "SKU").orElseThrow().version()).isZero();
        accept(tenant, "wrong-version", 0L).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SKU_VERSION_CONFLICT"));
        assertNoAcceptanceEffects(tenant);
        accept(tenant, "inactive", 1L).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SKU_NOT_ACTIVE"));
        assertNoAcceptanceEffects(tenant);
        jdbc.update("UPDATE bc_benefit_sku SET status='ACTIVE' WHERE tenant_id=?", tenant);
        accept(tenant, "current", 1L).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("SELECT sku_version FROM bc_award_item WHERE tenant_id=?",
                Long.class, tenant)).isEqualTo(1L);
    }

    @Test
    void changedOrRemovedVersionConflictsButOriginalSuccessReplaysAfterCutover() throws Exception {
        String tenant = seed("T-VERSION-REPLAY");
        String response = accept(tenant, "stable", 0L).andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String order = json.readTree(response).path("awardOrderNo").asText();
        jdbc.update("UPDATE bc_benefit_sku SET version=9,status='PAUSED' WHERE tenant_id=?", tenant);
        for (Long version : new Long[]{null, 9L}) {
            accept(tenant, "stable", version).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("IDEMPOTENCY_PAYLOAD_CONFLICT"));
        }
        accept(tenant, "stable", 0L).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.replay").value(true)).andExpect(jsonPath("$.awardOrderNo").value(order));
        assertThat(count("bc_award_order", tenant)).isEqualTo(1);
        assertThat(count("bc_outbox_event", tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sku_version FROM bc_award_item WHERE tenant_id=?", Long.class, tenant))
                .isZero();
    }

    @Test
    void httpWaitsForUncommittedCutoverThenReadsNewVersionAndRollsBack() throws Exception {
        String tenant = seed("T-VERSION-XLOCK");
        try (Connection writer = MYSQL.createConnection(""); var executor = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (var update = writer.prepareStatement("UPDATE bc_benefit_sku SET version=1 WHERE tenant_id=?")) {
                update.setString(1, tenant);
                update.executeUpdate();
            }
            var request = executor.submit(() -> accept(tenant, "waiting", 0L));
            try {
                awaitSkuLockWait();
                assertThat(request.isDone()).isFalse();
                writer.commit();
                request.get(10, TimeUnit.SECONDS).andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("SKU_VERSION_CONFLICT"));
            } finally {
                writer.rollback();
            }
        }
        assertNoAcceptanceEffects(tenant);
    }

    @Test
    void mapperSharedLockSurvivesReturnUntilSpringTransactionCommit() throws Exception {
        String tenant = seed("T-VERSION-SHARE");
        CountDownLatch readReturned = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var reader = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(lockedSkus.lockCurrent(tenant, "SKU").orElseThrow().version()).isZero();
                readReturned.countDown();
                try {
                    if (!allowCommit.await(15, TimeUnit.SECONDS)) throw new AssertionError("未收到提交信号");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return true;
            }));
            try {
                assertThat(readReturned.await(10, TimeUnit.SECONDS)).isTrue();
                var writer = executor.submit(() -> jdbc.update(
                        "UPDATE bc_benefit_sku SET version=1 WHERE tenant_id=?", tenant));
                awaitSkuLockWait();
                assertThat(writer.isDone()).isFalse();
                allowCommit.countDown();
                assertThat(reader.get(10, TimeUnit.SECONDS)).isTrue();
                assertThat(writer.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            } finally {
                allowCommit.countDown();
            }
        }
        assertThat(jdbc.queryForObject("SELECT version FROM bc_benefit_sku WHERE tenant_id=?", Long.class, tenant))
                .isEqualTo(1L);
        assertNoAcceptanceEffects(tenant);
    }

    @Test
    void inventoryLockWaitPastExpiryRollsBackAllAcceptanceWrites() throws Exception {
        String tenant = seed("T-VERSION-FINAL-TIME");
        Instant expiry = Instant.now().plusSeconds(3);
        jdbc.update("UPDATE bc_benefit_sku SET validity_type='ABSOLUTE',valid_from=?,valid_to=? WHERE tenant_id=?",
                Timestamp.from(expiry.minusSeconds(3600)), Timestamp.from(expiry), tenant);
        try (Connection writer = MYSQL.createConnection(""); var executor = Executors.newSingleThreadExecutor()) {
            writer.setAutoCommit(false);
            try (var update = writer.prepareStatement("UPDATE bc_inventory_account SET version=version WHERE tenant_id=?")) {
                update.setString(1, tenant); update.executeUpdate();
            }
            var request = executor.submit(() -> accept(tenant, "inventory-wait", 0L));
            try {
                awaitLockWait("bc_inventory_account");
                while (Instant.now().isBefore(expiry.plusMillis(10))) Thread.sleep(10);
                writer.commit();
                request.get(10, TimeUnit.SECONDS).andExpect(status().isUnprocessableEntity())
                        .andExpect(jsonPath("$.code").value("SKU_NOT_ACTIVE"));
            } finally { writer.rollback(); }
        }
        assertNoAcceptanceEffects(tenant);
    }

    /** 真实performance_schema锁等待证明请求已进入数据库，避免仅靠睡眠假定并发交错。 */
    private void awaitSkuLockWait() throws Exception {
        awaitLockWait("bc_benefit_sku");
    }

    private void awaitLockWait(String table) throws Exception {
        try (Connection observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             var query = observer.prepareStatement("""
                     SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                     JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                     WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME=?
                     """)) {
            query.setString(1, MYSQL.getDatabaseName());
            query.setString(2, table);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            do {
                try (var rows = query.executeQuery()) {
                    rows.next();
                    if (rows.getLong(1) > 0) return;
                }
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("未观察到真实SKU锁等待");
        }
    }

    /** 每例独立租户，不删除记录；模板、库存和计数器都是本容器测试输入。 */
    private String seed(String tenant) {
        jdbc.update("INSERT INTO bc_tenant_config(tenant_id,home_cell,status,version) VALUES (?,'cell-0','ENABLED',0)", tenant);
        jdbc.update("""
                INSERT INTO bc_benefit_sku(tenant_id,sku_id,benefit_type,status,version,validity_type,
                    relative_days,user_limit_per_day,user_limit_total)
                VALUES (?,'SKU','PHYSICAL','ACTIVE',0,'RELATIVE',7,5,10)
                """, tenant);
        jdbc.update("""
                INSERT INTO bc_channel_route(tenant_id,route_id,sku_id,priority_no,channel_code,owner_type,
                    reserve_mode,enabled,version)
                VALUES (?,'ROUTE','SKU',1,'CENTER_PHYSICAL','CENTER_QUOTA','LAZY',TRUE,0)
                """, tenant);
        jdbc.update("""
                INSERT INTO bc_inventory_account(tenant_id,account_id,sku_id,owner_type,owner_id,
                    available,reserved,issued,version)
                VALUES (?,'QUOTA','SKU','CENTER_QUOTA','platform',10,0,0,0)
                """, tenant);
        return tenant;
    }

    private ResultActions accept(String tenant, String key, Long version) throws Exception {
        var intent = new AwardIntent("1.0", "referral-test", key, null, "recipient:test", null,
                PartialPolicy.BEST_EFFORT, List.of(new AwardItemIntent("item", "SKU", BenefitType.PHYSICAL,
                null, null, 1, Map.of(), version)), Map.of());
        return mvc.perform(post("/openapi/v1/award-orders").header("X-Tenant-Id", tenant)
                .header("Idempotency-Key", key).contentType("application/json")
                .content(json.writeValueAsString(intent)));
    }

    private int count(String table, String tenant) {
        // table仅来自本测试固定常量，租户仍用参数化条件。
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant);
    }

    private void assertNoAcceptanceEffects(String tenant) {
        for (String table : List.of("bc_award_order", "bc_award_item", "bc_fulfillment_operation",
                "bc_outbox_event", "bc_inventory_ledger", "bc_user_limit_counter", "bc_user_limit_reservation")) {
            assertThat(count(table, tenant)).as(table).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT available FROM bc_inventory_account WHERE tenant_id=?", Long.class, tenant))
                .isEqualTo(10L);
        assertThat(jdbc.queryForObject("SELECT reserved FROM bc_inventory_account WHERE tenant_id=?", Long.class, tenant))
                .isZero();
    }
}
