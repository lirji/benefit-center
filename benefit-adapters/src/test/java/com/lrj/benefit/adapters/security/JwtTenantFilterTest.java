package com.lrj.benefit.adapters.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** JwtTenantFilter 的业务租户信任边界测试。 */
class JwtTenantFilterTest {
    private static final String MACHINE_SUBJECT = "benefit-center/marketing-benefit-service";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void delegatedCatalogCallUsesExplicitBusinessTenant() throws Exception {
        authenticate(jwt(MACHINE_SUBJECT, null, "login-owner", List.of("machine-audience")));
        JwtTenantFilter filter = new JwtTenantFilter(false, "machine-audience=benefit-center", MACHINE_SUBJECT);
        MockHttpServletRequest request = request("GET", "/internal/v1/catalog/skus", "retail-cn");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                observed.set(TenantContext.required()));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(observed).hasValue("retail-cn");
    }

    @Test
    void delegatedCatalogCallRequiresBusinessTenantHeader() throws Exception {
        authenticate(jwt(MACHINE_SUBJECT, null, "login-owner", List.of("machine-audience")));
        JwtTenantFilter filter = new JwtTenantFilter(false, "machine-audience=benefit-center", MACHINE_SUBJECT);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/internal/v1/catalog/skus", null), response,
                (ignoredRequest, ignoredResponse) -> { });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("\"code\":\"BENEFIT_TENANT_REQUIRED\"");
    }

    @Test
    void tenantIdClaimWinsAndOwnerIsNeverUsedAsBusinessTenant() throws Exception {
        authenticate(jwt("human-user", "retail-cn", "benefit-center", List.of("legacy-audience")));
        JwtTenantFilter filter = new JwtTenantFilter(false, "legacy-audience=benefit-center", MACHINE_SUBJECT);
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request("GET", "/admin/v1/skus", "retail-cn"), new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> observed.set(TenantContext.required()));

        assertThat(observed).hasValue("retail-cn");
    }

    @Test
    void casdoorPropertiesTenantIsNormalizedWithoutUsingOwnerOrAudience() throws Exception {
        authenticate(jwtWithProperties("human-user", "dev-tenant", "benefit-center",
                List.of("legacy-audience")));
        JwtTenantFilter filter = new JwtTenantFilter(false, "legacy-audience=legacy-tenant", MACHINE_SUBJECT);
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request("GET", "/admin/v1/skus", null), new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> observed.set(TenantContext.required()));

        assertThat(observed).hasValue("dev-tenant");
    }

    @Test
    void conflictingTenantHeaderReturnsStableMismatchCode() throws Exception {
        authenticate(jwt("human-user", "retail-cn", "benefit-center", List.of("legacy-audience")));
        JwtTenantFilter filter = new JwtTenantFilter(false, "legacy-audience=benefit-center", MACHINE_SUBJECT);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/admin/v1/skus", "benefit-center"), response,
                (ignoredRequest, ignoredResponse) -> { });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"BENEFIT_TENANT_MISMATCH\"");
    }

    @Test
    void legacyAudienceMappingRemainsAvailableForHumanTokens() throws Exception {
        authenticate(jwt("human-user", null, "login-owner", List.of("legacy-audience")));
        JwtTenantFilter filter = new JwtTenantFilter(false, "legacy-audience=benefit-center", MACHINE_SUBJECT);
        AtomicReference<String> observed = new AtomicReference<>();

        filter.doFilter(request("GET", "/admin/v1/skus", null), new MockHttpServletResponse(),
                (ignoredRequest, ignoredResponse) -> observed.set(TenantContext.required()));

        assertThat(observed).hasValue("benefit-center");
    }

    @Test
    void humanTokenWithoutBusinessTenantOrMappedAudienceFailsClosed() throws Exception {
        authenticate(jwt("human-user", null, "benefit-center", List.of("unknown-audience")));
        JwtTenantFilter filter = new JwtTenantFilter(false, "legacy-audience=legacy-tenant", MACHINE_SUBJECT);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request("GET", "/admin/v1/skus", null), response,
                (ignoredRequest, ignoredResponse) -> { });

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"BENEFIT_TENANT_UNMAPPED\"");
    }

    private static MockHttpServletRequest request(String method, String uri, String tenantId) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        if (tenantId != null) request.addHeader("X-Tenant-Id", tenantId);
        return request;
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static Jwt jwt(String subject, String tenantId, String owner, List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.parse("2026-09-06T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-07T00:00:00Z"))
                .audience(audience)
                .claim("owner", owner);
        if (tenantId != null) builder.claim("tenant_id", tenantId);
        return builder.build();
    }

    private static Jwt jwtWithProperties(String subject, String tenantId, String owner, List<String> audience) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.parse("2026-09-06T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-07T00:00:00Z"))
                .audience(audience)
                .claim("owner", owner)
                .claim("properties", Map.of("tenant_id", tenantId))
                .build();
    }
}
