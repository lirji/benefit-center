package com.lrj.benefit.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benefit_portal;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "benefit.security.dev-mode=true",
        "benefit.worker.enabled=false",
        "benefit.outbox.enabled=false"
})
@AutoConfigureMockMvc
@Transactional
class PortalLandingEndToEndTest {
    @Autowired MockMvc mvc;

    @Test
    void healthzAllowsAnonymousCorsGet() throws Exception {
        mvc.perform(get("/healthz").header(HttpHeaders.ORIGIN, "http://localhost:5274"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(content().string("ok"));
    }

    @Test
    void healthzAllowsAnonymousCorsPreflight() throws Exception {
        mvc.perform(options("/healthz")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5274")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*"));
    }

    @Test
    void rootNoLongerServesHtmlLanding() throws Exception {
        mvc.perform(get("/")).andExpect(status().isNotFound());
    }
}
