package com.lrj.benefit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benefit_portal_secure;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "benefit.security.dev-mode=false",
        "benefit.security.jwk-set-uri=http://127.0.0.1:1/jwks",
        "benefit.worker.enabled=false",
        "benefit.outbox.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class PortalLandingSecureModeTest {
    @Autowired MockMvc mvc;

    @Test
    void healthzStaysPublicWithoutJwt() throws Exception {
        mvc.perform(get("/healthz").header(HttpHeaders.ORIGIN, "http://localhost:5274"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
                .andExpect(content().string("ok"));
    }

    @Test
    void rootAndConsoleApisRequireJwt() throws Exception {
        mvc.perform(get("/")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/v1/console/overview")).andExpect(status().isUnauthorized());
    }

    @Test
    void awardApiStillRequiresJwt() throws Exception {
        mvc.perform(get("/openapi/v1/award-orders/ORD-1"))
                .andExpect(status().isUnauthorized());
    }
}
