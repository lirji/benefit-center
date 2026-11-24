package com.lrj.benefit.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.adapters.channel.CenterWalletCashAdapter;
import com.lrj.benefit.application.port.in.CatalogAdminUseCase;
import com.lrj.benefit.application.port.in.ExecuteFulfillmentUseCase;
import com.lrj.benefit.application.port.in.ExecuteRemediationUseCase;
import com.lrj.benefit.application.service.BenefitApplicationException;
import com.lrj.benefit.contract.*;
import com.lrj.benefit.domain.model.InventoryOwnerType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benefit_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "benefit.security.dev-mode=true",
        "benefit.channel.center-wallet-cash-enabled=true",
        "benefit.worker.enabled=false",
        "benefit.outbox.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class AwardOrderEndToEndTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired ExecuteFulfillmentUseCase fulfillment;
    @Autowired CatalogAdminUseCase catalogAdmin;
    @Autowired ExecuteRemediationUseCase remediation;

    @BeforeEach
    void seedCatalog() {
        jdbc.update("INSERT INTO bc_tenant_config(tenant_id,home_cell,status,version) VALUES ('T1','cell-0','ENABLED',0)");
        jdbc.update("""
                INSERT INTO bc_benefit_sku
                (tenant_id,sku_id,benefit_type,currency,face_value_minor,status,metadata_json,version)
                VALUES ('T1','CODE-10','REDEMPTION_CODE',NULL,NULL,'ENABLED',NULL,0)
                """);
        jdbc.update("""
                INSERT INTO bc_channel_route
                (tenant_id,route_id,sku_id,priority_no,channel_code,owner_type,fallback_route_id,
                 reserve_mode,enabled,config_ref,version)
                VALUES ('T1','R-CODE','CODE-10',1,'CENTER_CODE','CENTER_STOCK',NULL,'LAZY',TRUE,NULL,0)
                """);
        jdbc.update("""
                INSERT INTO bc_inventory_account
                (tenant_id,account_id,sku_id,owner_type,owner_id,available,reserved,issued,version,snapshot_at)
                VALUES ('T1','Q-CODE','CODE-10','CENTER_QUOTA','platform',10,0,0,0,NULL),
                       ('T1','S-CODE','CODE-10','CENTER_STOCK','code-pool',10,0,0,0,NULL)
                """);
        jdbc.update("""
                INSERT INTO bc_code_asset
                (tenant_id,code_asset_id,sku_id,code_hash,cipher_text,key_version,status,reserved_item_no,expires_at,version)
                VALUES ('T1','C1','CODE-10','hash1','encrypted','k1','AVAILABLE',NULL,?,0)
                """, java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO bc_benefit_sku
                (tenant_id,sku_id,benefit_type,currency,face_value_minor,status,metadata_json,version)
                VALUES ('T1','PHYSICAL-1','PHYSICAL',NULL,NULL,'ENABLED',NULL,0)
                """);
        jdbc.update("""
                INSERT INTO bc_channel_route
                (tenant_id,route_id,sku_id,priority_no,channel_code,owner_type,fallback_route_id,
                 reserve_mode,enabled,config_ref,version)
                VALUES ('T1','R-PHYSICAL','PHYSICAL-1',1,'CENTER_PHYSICAL','CENTER_STOCK',NULL,'LAZY',TRUE,NULL,0)
                """);
    }

    @Test
    void acceptsReplaysAndFulfillsRedemptionCodeExactlyOnce() throws Exception {
        AwardIntent intent = new AwardIntent("1.0", "drools", "REQ-1", "ORDER-1", "recipient:1", null,
                PartialPolicy.BEST_EFFORT,
                List.of(new AwardItemIntent("item-1", "CODE-10", BenefitType.REDEMPTION_CODE,
                        null, null, 1, Map.of())), Map.of("traceId", "trace-1"));
        String body = json.writeValueAsString(intent);

        String response = mvc.perform(post("/openapi/v1/award-orders")
                        .header("X-Tenant-Id", "T1").header("Idempotency-Key", "REQ-1")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.replay").value(false))
                .andReturn().getResponse().getContentAsString();
        JsonNode accepted = json.readTree(response);
        String orderNo = accepted.path("awardOrderNo").asText();

        var batch = fulfillment.runBatch("T1", 10, "test-worker");
        assertThat(batch.succeeded()).isEqualTo(1);

        mvc.perform(get("/openapi/v1/award-orders/{orderNo}", orderNo).header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientRef").value("recipient:1"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].status").value("SUCCEEDED"));

        jdbc.update("UPDATE bc_benefit_sku SET status='DISABLED' WHERE tenant_id='T1' AND sku_id='CODE-10'");
        jdbc.update("UPDATE bc_channel_route SET enabled=FALSE WHERE tenant_id='T1' AND route_id='R-CODE'");

        mvc.perform(post("/openapi/v1/award-orders")
                        .header("X-Tenant-Id", "T1").header("Idempotency-Key", "REQ-1")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.awardOrderNo").value(orderNo))
                .andExpect(jsonPath("$.replay").value(true));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bc_award_order", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bc_award_ledger_entry", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT available FROM bc_inventory_account WHERE account_id='Q-CODE'",
                Long.class)).isEqualTo(9L);
    }

    @Test
    void creditsCenterWalletExactlyOnceAfterDispatchLeaseExpires() throws Exception {
        jdbc.update("""
                INSERT INTO bc_benefit_sku
                (tenant_id,sku_id,benefit_type,currency,face_value_minor,status,version,validity_type,relative_days)
                VALUES ('T1','CASH-WALLET','CASH','CNY',100,'ACTIVE',0,'RELATIVE',30)
                """);
        jdbc.update("""
                INSERT INTO bc_sku_template_version
                (tenant_id,sku_id,benefit_type,currency,face_value_minor,status,version,validity_type,
                 relative_days,created_at)
                VALUES ('T1','CASH-WALLET','CASH','CNY',100,'ACTIVE',0,'RELATIVE',30,CURRENT_TIMESTAMP)
                """);
        catalogAdmin.saveRoute("T1", new CatalogAdminUseCase.RouteCommand("R-WALLET", "CASH-WALLET", 1,
                CenterWalletCashAdapter.CHANNEL_CODE, InventoryOwnerType.CENTER_QUOTA, null, "LAZY",
                true, null, null));
        jdbc.update("""
                INSERT INTO bc_inventory_account
                (tenant_id,account_id,sku_id,owner_type,owner_id,available,reserved,issued,version)
                VALUES ('T1','Q-WALLET','CASH-WALLET','CENTER_QUOTA','platform',3,0,0,0)
                """);
        AwardIntent intent = new AwardIntent("1.0", "drools", "REQ-CASH", "ORDER-CASH", "recipient:cash", null,
                PartialPolicy.BEST_EFFORT, List.of(new AwardItemIntent("cash", "CASH-WALLET", BenefitType.CASH,
                        100L, "CNY", 1, Map.of())), Map.of());
        String body = json.writeValueAsString(intent);
        String response = mvc.perform(post("/openapi/v1/award-orders")
                        .header("X-Tenant-Id", "T1").header("Idempotency-Key", "REQ-CASH")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String orderNo = json.readTree(response).path("awardOrderNo").asText();

        // 模拟工作进程在履约事务提交前退出。新租约查询原操作，钱包只能入账一次。
        jdbc.update("UPDATE bc_award_item SET status='DISPATCHING',version=version+1");
        jdbc.update("""
                UPDATE bc_fulfillment_operation
                SET status='DISPATCHING',lease_owner='dead-worker',lease_until=?,version=version+1
                """, Timestamp.from(Instant.EPOCH));
        assertThat(fulfillment.runBatch("T1", 10, "replacement-worker").succeeded()).isEqualTo(1);
        assertThat(fulfillment.runBatch("T1", 10, "next-worker").claimed()).isZero();

        mvc.perform(post("/openapi/v1/award-orders")
                        .header("X-Tenant-Id", "T1").header("Idempotency-Key", "REQ-CASH")
                        .contentType("application/json").content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.awardOrderNo").value(orderNo))
                .andExpect(jsonPath("$.replay").value(true));
        mvc.perform(get("/openapi/v1/award-orders/{orderNo}", orderNo).header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.items[0].walletEntryId").isNotEmpty());
        mvc.perform(get("/admin/v1/wallets/{subjectRef}", "recipient:cash").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEntries").value(1))
                .andExpect(jsonPath("$.cashBalances[0].currency").value("CNY"))
                .andExpect(jsonPath("$.cashBalances[0].balanceMinor").value(100));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bc_wallet_balance_ledger", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bc_fulfillment_operation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT issued FROM bc_inventory_account WHERE account_id='Q-WALLET'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    void rejectsCenterWalletCashRouteForNonCashSku() {
        var route = new CatalogAdminUseCase.RouteCommand("R-WALLET-INVALID", "CODE-10", 2,
                CenterWalletCashAdapter.CHANNEL_CODE, InventoryOwnerType.CENTER_QUOTA, null, "LAZY",
                true, null, null);
        assertThatThrownBy(() -> catalogAdmin.saveRoute("T1", route))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void combinationConvergesToPartialSuccessWhenOneItemHasNoQuota() throws Exception {
        AwardIntent intent = new AwardIntent("1.0", "drools", "REQ-PARTIAL", "ORDER-2", "recipient:2", null,
                PartialPolicy.BEST_EFFORT, List.of(
                new AwardItemIntent("code", "CODE-10", BenefitType.REDEMPTION_CODE, null, null, 1, Map.of()),
                new AwardItemIntent("physical", "PHYSICAL-1", BenefitType.PHYSICAL, null, null, 1, Map.of())
        ), Map.of());
        String response = mvc.perform(post("/openapi/v1/award-orders")
                        .header("X-Tenant-Id", "T1").header("Idempotency-Key", "REQ-PARTIAL")
                        .contentType("application/json").content(json.writeValueAsString(intent)))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String orderNo = json.readTree(response).path("awardOrderNo").asText();

        assertThat(fulfillment.runBatch("T1", 10, "test-worker").succeeded()).isEqualTo(1);

        mvc.perform(get("/openapi/v1/award-orders/{orderNo}", orderNo).header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL_SUCCEEDED"))
                .andExpect(jsonPath("$.items[?(@.failureCode == 'CENTER_QUOTA_EXHAUSTED')]").exists());

        String itemNo = jdbc.queryForObject("""
                SELECT item_no FROM bc_award_item WHERE tenant_id='T1' AND order_no=? AND sku_id='PHYSICAL-1'
                """, String.class, orderNo);
        String originalOperationNo = jdbc.queryForObject("""
                SELECT operation_no FROM bc_fulfillment_operation
                WHERE tenant_id='T1' AND item_no=? AND status='FAILED_FINAL'
                """, String.class, itemNo);
        jdbc.update("""
                INSERT INTO bc_inventory_account
                (tenant_id,account_id,sku_id,owner_type,owner_id,available,reserved,issued,version,snapshot_at)
                VALUES ('T1','Q-PHYSICAL','PHYSICAL-1','CENTER_QUOTA','platform',10,0,0,0,NULL),
                       ('T1','S-PHYSICAL','PHYSICAL-1','CENTER_STOCK','warehouse',10,0,0,0,NULL)
                """);
        var accepted = remediation.accept("T1", new RemediationCommand("CMD-REISSUE-1",
                RemediationAction.REISSUE, itemNo, originalOperationNo,
                "recon confirmed pre-dispatch inventory failure", "APPROVAL-1"));
        assertThat(accepted.status()).isEqualTo("APPROVED");
        assertThat(remediation.accept("T1", new RemediationCommand("CMD-REISSUE-1",
                RemediationAction.REISSUE, itemNo, originalOperationNo,
                "recon confirmed pre-dispatch inventory failure", "APPROVAL-1")).remediationNo())
                .isEqualTo(accepted.remediationNo());
        assertThatThrownBy(() -> remediation.accept("T1", new RemediationCommand("CMD-REISSUE-1",
                RemediationAction.REISSUE, itemNo, originalOperationNo,
                "a different payload", "APPROVAL-1")))
                .isInstanceOf(BenefitApplicationException.class)
                .hasMessageContaining("reused");
        assertThat(remediation.execute("T1", accepted.remediationNo(), "test-remediation").status())
                .isEqualTo("DISPATCHING");
        assertThat(fulfillment.runBatch("T1", 10, "test-worker-2").succeeded()).isEqualTo(1);

        mvc.perform(get("/openapi/v1/award-orders/{orderNo}", orderNo).header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bc_award_ledger_entry WHERE tenant_id='T1'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void approvedReverseUsesOriginalRouteEvenWhenNewIssuanceWasDisabled() throws Exception {
        ItemOperation issued = acceptAndFulfillCode("REQ-REVERSE");
        var approved = remediation.accept("T1", new RemediationCommand("CMD-REVERSE-1",
                RemediationAction.REVERSE, issued.itemNo(), issued.operationNo(),
                "approved customer refund", "APPROVAL-REVERSE-1"));
        jdbc.update("UPDATE bc_channel_route SET enabled=FALSE WHERE tenant_id='T1' AND route_id='R-CODE'");

        assertThat(remediation.execute("T1", approved.remediationNo(), "reverse-worker").status())
                .isEqualTo("DISPATCHING");
        assertThat(fulfillment.runBatch("T1", 10, "fulfillment-worker").succeeded()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bc_award_item WHERE item_no=?",
                String.class, issued.itemNo())).isEqualTo("REVERSED");
        assertThat(jdbc.queryForObject("SELECT available FROM bc_inventory_account WHERE account_id='Q-CODE'",
                Long.class)).isEqualTo(10L);
    }

    @Test
    void executionRechecksOriginalOperationAfterApproval() throws Exception {
        ItemOperation issued = acceptAndFulfillCode("REQ-RECHECK");
        var approved = remediation.accept("T1", new RemediationCommand("CMD-REVERSE-RECHECK",
                RemediationAction.REVERSE, issued.itemNo(), issued.operationNo(),
                "approved before provider state changed", "APPROVAL-RECHECK"));
        jdbc.update("UPDATE bc_fulfillment_operation SET status='UNKNOWN' WHERE operation_no=?",
                issued.operationNo());

        assertThatThrownBy(() -> remediation.execute("T1", approved.remediationNo(), "reverse-worker"))
                .isInstanceOf(BenefitApplicationException.class)
                .hasMessageContaining("UNKNOWN");
    }

    private ItemOperation acceptAndFulfillCode(String requestId) throws Exception {
        AwardIntent intent = new AwardIntent("1.0", "drools", requestId, null, "recipient:test", null,
                PartialPolicy.BEST_EFFORT,
                List.of(new AwardItemIntent("code", "CODE-10", BenefitType.REDEMPTION_CODE,
                        null, null, 1, Map.of())), Map.of());
        mvc.perform(post("/openapi/v1/award-orders")
                        .header("X-Tenant-Id", "T1").header("Idempotency-Key", requestId)
                        .contentType("application/json").content(json.writeValueAsString(intent)))
                .andExpect(status().isAccepted());
        assertThat(fulfillment.runBatch("T1", 10, "issue-worker").succeeded()).isEqualTo(1);
        return jdbc.queryForObject("""
                SELECT i.item_no,o.operation_no
                FROM bc_award_item i JOIN bc_fulfillment_operation o
                  ON o.tenant_id=i.tenant_id AND o.item_no=i.item_no
                WHERE i.tenant_id='T1' AND i.client_item_id='code' AND o.status='SUCCEEDED'
                """, (rs, row) -> new ItemOperation(rs.getString(1), rs.getString(2)));
    }

    private record ItemOperation(String itemNo, String operationNo) {}
}
