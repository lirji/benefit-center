package com.lrj.benefit.web;

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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benefit_console;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "benefit.security.dev-mode=true",
        "benefit.worker.enabled=false",
        "benefit.outbox.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class ConsoleQueryEndToEndTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        Timestamp now = Timestamp.from(Instant.parse("2026-09-04T04:00:00Z"));
        jdbc.update("INSERT INTO bc_tenant_config(tenant_id,home_cell,status,version) VALUES ('T1','cell-0','ENABLED',3)");
        jdbc.update("INSERT INTO bc_tenant_config(tenant_id,home_cell,status,version) VALUES ('T2','cell-1','ENABLED',1)");
        jdbc.update("""
                INSERT INTO bc_benefit_sku
                (tenant_id,sku_id,benefit_type,currency,face_value_minor,status,metadata_json,version)
                VALUES ('T1','SKU-1','CASH','CNY',100,'ENABLED',NULL,2),
                       ('T2','SKU-X','COUPON',NULL,NULL,'ENABLED',NULL,0)
                """);
        jdbc.update("""
                INSERT INTO bc_channel_route
                (tenant_id,route_id,sku_id,priority_no,channel_code,owner_type,fallback_route_id,
                 reserve_mode,enabled,config_ref,version)
                VALUES ('T1','R-1','SKU-1',1,'CENTER_CASH','CENTER_QUOTA',NULL,'LAZY',TRUE,NULL,1),
                       ('T2','R-X','SKU-X',1,'CENTER_COUPON','CENTER_QUOTA',NULL,'LAZY',TRUE,NULL,0)
                """);
        jdbc.update("""
                INSERT INTO bc_inventory_account
                (tenant_id,account_id,sku_id,owner_type,owner_id,available,reserved,issued,version,snapshot_at)
                VALUES ('T1','A-1','SKU-1','CENTER_QUOTA','platform',80,2,3,4,NULL),
                       ('T2','A-X','SKU-X','CENTER_QUOTA','platform',9,0,0,0,NULL)
                """);
        jdbc.update("""
                INSERT INTO bc_award_order
                (tenant_id,order_no,source_system,source_request_id,source_business_no,recipient_ref,request_hash,
                 partial_policy,status,home_cell,trace_id,version,created_at,updated_at)
                VALUES ('T1','ORD-1','drools','req-1',NULL,'u1','h1','BEST_EFFORT','PARTIAL_SUCCEEDED','cell-0',NULL,0,?,?),
                       ('T2','ORD-X','drools','req-x',NULL,'u2','h2','BEST_EFFORT','PARTIAL_SUCCEEDED','cell-1',NULL,0,?,?)
                """, now, now, now, now);
        jdbc.update("""
                INSERT INTO bc_award_item
                (tenant_id,item_no,order_no,client_item_id,sku_id,benefit_type,amount_minor,currency,quantity,
                 status,route_id,failure_code,retry_at,version,created_at,updated_at)
                VALUES ('T1','IT-1','ORD-1','c1','SKU-1','CASH',100,'CNY',1,'UNKNOWN',NULL,NULL,NULL,0,?,?),
                       ('T2','IT-X','ORD-X','cx','SKU-X','COUPON',NULL,NULL,1,'UNKNOWN',NULL,NULL,NULL,0,?,?)
                """, now, now, now, now);
        jdbc.update("""
                INSERT INTO bc_fulfillment_operation
                (tenant_id,operation_no,item_no,operation_type,remediation_no,status,idempotency_key,
                 lease_owner,lease_until,attempt_count,unknown_since,version,created_at,updated_at)
                VALUES ('T1','OP-1','IT-1','ISSUE',NULL,'UNKNOWN','idem-1',NULL,NULL,0,?,0,?,?),
                       ('T2','OP-X','IT-X','ISSUE',NULL,'UNKNOWN','idem-x',NULL,NULL,0,?,0,?,?)
                """, now, now, now, now, now, now);
        jdbc.update("""
                INSERT INTO bc_remediation_order
                (tenant_id,remediation_no,source_system,external_command_id,item_no,action_type,original_operation_no,
                 reason,approval_ref,status,version,created_at,updated_at)
                VALUES ('T1','RM-1','recon','cmd-1','IT-1','REISSUE','OP-1','retry',NULL,'PROPOSED',0,?,?),
                       ('T2','RM-X','recon','cmd-x','IT-X','REISSUE','OP-X','retry',NULL,'PROPOSED',0,?,?)
                """, now, now, now, now);
        jdbc.update("""
                INSERT INTO bc_code_asset
                (tenant_id,code_asset_id,sku_id,code_hash,cipher_text,key_version,status,reserved_item_no,expires_at,version)
                VALUES ('T1','C-1','SKU-1','abcdefghijklmnop','SUPER-SECRET-PLAIN','k1','AVAILABLE',NULL,?,0),
                       ('T2','C-X','SKU-X','xxxxxxxxxxxxxxxx','OTHER-SECRET','k1','AVAILABLE',NULL,?,0)
                """, Timestamp.from(Instant.parse("2026-12-01T00:00:00Z")),
                Timestamp.from(Instant.parse("2026-12-01T00:00:00Z")));
        for (int i = 0; i < 51; i++) {
            jdbc.update("""
                    INSERT INTO bc_benefit_sku
                    (tenant_id,sku_id,benefit_type,currency,face_value_minor,status,metadata_json,version)
                    VALUES ('T1',?,'COUPON',NULL,NULL,'ENABLED',NULL,0)
                    """, "SKU-PAD-" + i);
        }
    }

    @Test
    void meReturnsDevSandboxIdentity() throws Exception {
        mvc.perform(get("/admin/v1/console/me").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("T1"))
                .andExpect(jsonPath("$.subject").value("dev"))
                .andExpect(jsonPath("$.scopes[0]").value("benefit.admin"));
    }

    @Test
    void overviewCountsOnlyCurrentTenant() throws Exception {
        mvc.perform(get("/admin/v1/console/overview").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unknownOps").value(1))
                .andExpect(jsonPath("$.partialOrders").value(1))
                .andExpect(jsonPath("$.pendingRemediations").value(1))
                .andExpect(jsonPath("$.skuCount").value(52))
                .andExpect(jsonPath("$.enabledRoutes").value(1));
    }

    @Test
    void catalogAndInventoryStayTenantScoped() throws Exception {
        mvc.perform(get("/admin/v1/tenants/current").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("T1"))
                .andExpect(jsonPath("$.homeCell").value("cell-0"))
                .andExpect(jsonPath("$.version").value(3));
        mvc.perform(get("/admin/v1/skus").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.skuId=='SKU-X')]").isEmpty());
        mvc.perform(get("/internal/v1/catalog/skus").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.skuId=='SKU-1')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.skuId=='SKU-X')]").isEmpty());
        mvc.perform(get("/admin/v1/routes").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].routeId").value("R-1"))
                .andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(get("/admin/v1/inventory/accounts").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountId").value("A-1"))
                .andExpect(jsonPath("$[0].available").value(80));
    }

    @Test
    void listApisCapAtFifty() throws Exception {
        mvc.perform(get("/admin/v1/skus").param("limit", "200").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(50)));
    }

    @Test
    void attentionAndRemediationsIgnoreOtherTenants() throws Exception {
        mvc.perform(get("/admin/v1/console/attention-orders").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].orderNo").value("ORD-1"));
        mvc.perform(get("/admin/v1/remediations").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].remediationNo").value("RM-1"));
    }

    @Test
    void codeAssetsNeverReturnCipherText() throws Exception {
        String body = mvc.perform(get("/admin/v1/code-assets").header("X-Tenant-Id", "T1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codeAssetId").value("C-1"))
                .andExpect(jsonPath("$[0].codeHashPrefix").value("abcdefgh"))
                .andExpect(jsonPath("$[0].cipherText").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("SUPER-SECRET-PLAIN");
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("cipher");
    }

    @Test
    void missingTenantIsRejected() throws Exception {
        mvc.perform(get("/admin/v1/console/overview"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", not("")));
    }
}
