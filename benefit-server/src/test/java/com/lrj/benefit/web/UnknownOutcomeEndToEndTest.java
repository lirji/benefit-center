package com.lrj.benefit.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lrj.benefit.application.port.in.ExecuteFulfillmentUseCase;
import com.lrj.benefit.application.port.out.ChannelAdapter;
import com.lrj.benefit.contract.*;
import com.lrj.benefit.domain.model.AdapterCapabilities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benefit_unknown;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa", "spring.datasource.password=", "benefit.security.dev-mode=true"
})
@AutoConfigureMockMvc
@Import(UnknownOutcomeEndToEndTest.AdapterConfig.class)
@Transactional
class UnknownOutcomeEndToEndTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired ExecuteFulfillmentUseCase fulfillment;

    @BeforeEach void seed() {
        jdbc.update("INSERT INTO bc_tenant_config VALUES ('T1','cell-0','ENABLED',0)");
        jdbc.update("INSERT INTO bc_benefit_sku VALUES ('T1','COUPON-1','COUPON',NULL,NULL,'ENABLED',NULL,0)");
        jdbc.update("""
                INSERT INTO bc_channel_route VALUES
                ('T1','R-CHANNEL','COUPON-1',1,'TEST_UNKNOWN','CENTER_QUOTA','R-FALLBACK','EAGER',TRUE,NULL,0),
                ('T1','R-FALLBACK','COUPON-1',2,'CENTER_PHYSICAL','CENTER_STOCK',NULL,'LAZY',TRUE,NULL,0)
                """);
        jdbc.update("""
                INSERT INTO bc_inventory_account VALUES
                ('T1','Q1','COUPON-1','CENTER_QUOTA','platform',10,0,0,0,NULL),
                ('T1','S1','COUPON-1','CENTER_STOCK','fallback',10,0,0,0,NULL)
                """);
    }

    @Test void unknownQueriesOriginalOperationWithoutCreatingFallbackIssue() throws Exception {
        accept("REQ-U");
        assertThat(jdbc.queryForObject("SELECT available FROM bc_inventory_account WHERE account_id='S1'",
                Long.class)).isEqualTo(9L);
        assertThat(jdbc.queryForObject("SELECT reserved FROM bc_inventory_account WHERE account_id='S1'",
                Long.class)).isEqualTo(1L);

        assertThat(fulfillment.runBatch("T1", 10, "w1").unknown()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bc_fulfillment_operation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bc_fulfillment_operation", String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT route_id FROM bc_award_item", String.class)).isEqualTo("R-CHANNEL");

        jdbc.update("UPDATE bc_fulfillment_operation SET next_attempt_at=?", Timestamp.from(Instant.EPOCH));
        assertThat(fulfillment.runBatch("T1", 10, "w2").succeeded()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bc_fulfillment_operation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bc_award_item", String.class)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("SELECT available FROM bc_inventory_account WHERE account_id='S1'",
                Long.class)).isEqualTo(10L);
        assertThat(jdbc.queryForObject("SELECT reserved FROM bc_inventory_account WHERE account_id='S1'",
                Long.class)).isZero();
    }

    @Test void expiredDispatchLeaseRecoversAsQueryInsteadOfSecondIssue() throws Exception {
        accept("REQ-CRASH");
        jdbc.update("UPDATE bc_award_item SET status='DISPATCHING',version=version+1");
        jdbc.update("""
                UPDATE bc_fulfillment_operation
                SET status='DISPATCHING',lease_owner='dead-worker',lease_until=?,version=version+1
                """, Timestamp.from(Instant.EPOCH));

        assertThat(fulfillment.runBatch("T1", 10, "replacement-worker").succeeded()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bc_fulfillment_operation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM bc_award_item", String.class)).isEqualTo("SUCCEEDED");
    }

    private void accept(String requestId) throws Exception {
        AwardIntent intent = new AwardIntent("1.0", "test", requestId, null, "recipient", null,
                PartialPolicy.BEST_EFFORT, List.of(new AwardItemIntent("i", "COUPON-1", BenefitType.COUPON,
                null, null, 1, Map.of())), Map.of());
        mvc.perform(post("/openapi/v1/award-orders").header("X-Tenant-Id", "T1")
                        .header("Idempotency-Key", requestId).contentType("application/json")
                        .content(json.writeValueAsString(intent))).andExpect(status().isAccepted());
    }

    @TestConfiguration static class AdapterConfig {
        @Bean ChannelAdapter testUnknownAdapter() {
            return new ChannelAdapter() {
                public String channelCode() { return "TEST_UNKNOWN"; }
                public AdapterCapabilities capabilities() { return new AdapterCapabilities(true, true, false, false); }
                public ChannelResult issue(ChannelCommand command) {
                    return new ChannelResult(ChannelResult.ResultType.UNKNOWN, null, "TIMEOUT", null);
                }
                public ChannelResult query(ChannelCommand command) {
                    return new ChannelResult(ChannelResult.ResultType.SUCCEEDED, "provider-1", null, null);
                }
                public ChannelResult reverse(ChannelCommand command) {
                    return new ChannelResult(ChannelResult.ResultType.FINAL_FAILURE, null, "UNSUPPORTED", null);
                }
            };
        }
    }
}
