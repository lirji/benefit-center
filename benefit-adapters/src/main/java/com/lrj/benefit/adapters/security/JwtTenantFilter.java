package com.lrj.benefit.adapters.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 从已验证 JWT 解析业务租户，并对受控的机器账号委派请求校验显式租户头。
 * 登录组织 owner 不是业务租户；新令牌以 tenant_id 为准，audience 映射仅保留给旧令牌兼容。
 */
public final class JwtTenantFilter extends OncePerRequestFilter {
    private static final String TENANT_HEADER = "X-Tenant-Id";
    private final boolean devMode;
    private final Map<String, String> audienceTenants;
    private final Set<String> delegatedTenantSubjects;

    public JwtTenantFilter(boolean devMode, String audienceTenantMappings) {
        this(devMode, audienceTenantMappings, "");
    }

    public JwtTenantFilter(boolean devMode, String audienceTenantMappings, String delegatedTenantSubjects) {
        this.devMode = devMode;
        this.audienceTenants = parse(audienceTenantMappings);
        this.delegatedTenantSubjects = parseSet(delegatedTenantSubjects);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/healthz".equals(path);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        try {
            String tenant = resolveTenant(request);
            if (tenant != null) TenantContext.set(tenant);
            chain.doFilter(request, response);
        } catch (TenantResolutionException invalidTenant) {
            writeProblem(response, invalidTenant);
        } finally {
            TenantContext.clear();
        }
    }

    private String resolveTenant(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String asserted = normalized(request.getHeader(TENANT_HEADER));
        if (devMode) return asserted;
        if (!(authentication instanceof JwtAuthenticationToken token)) return null;

        Jwt jwt = token.getToken();
        if (delegatedTenantSubjects.contains(jwt.getSubject()) && isDelegatedOperation(request)) {
            if (asserted == null) {
                throw new TenantResolutionException("BENEFIT_TENANT_REQUIRED",
                        "delegated machine request requires X-Tenant-Id");
            }
            return asserted;
        }

        String tenant = tenantFromJwt(jwt);
        if (asserted != null && !tenant.equals(asserted)) {
            throw new TenantResolutionException("BENEFIT_TENANT_MISMATCH",
                    "tenant header does not match the signed business tenant");
        }
        return tenant;
    }

    /** 机器账号只能在两个明确的服务间契约上代入上游已验证的业务租户。 */
    private boolean isDelegatedOperation(HttpServletRequest request) {
        return ("GET".equals(request.getMethod()) && "/internal/v1/catalog/skus".equals(request.getRequestURI()))
                || ("POST".equals(request.getMethod()) && "/openapi/v1/award-orders".equals(request.getRequestURI()));
    }

    private String tenantFromJwt(Jwt jwt) {
        // Casdoor 会把管理员维护的用户属性保留在 properties；同时兼容已归一到顶层的令牌。
        String tenantClaim = firstNonBlank(
                normalized(claimAsString(jwt, "tenant_id")),
                propertyClaim(jwt, "tenant_id"));
        if (tenantClaim != null) return tenantClaim;
        for (String audience : jwt.getAudience()) {
            String tenant = audienceTenants.get(audience);
            if (tenant != null) return tenant;
        }
        throw new TenantResolutionException("BENEFIT_TENANT_UNMAPPED",
                "JWT has no tenant_id and its audience is not mapped to a benefit tenant");
    }

    private static String claimAsString(Jwt jwt, String name) {
        Object value = jwt.getClaims().get(name);
        return value == null ? null : String.valueOf(value);
    }

    /** 读取 Casdoor 自定义用户属性；owner 绝不参与业务租户解析。 */
    private static String propertyClaim(Jwt jwt, String name) {
        Object properties = jwt.getClaims().get("properties");
        if (!(properties instanceof Map<?, ?> values)) return null;
        Object value = values.get(name);
        return value == null ? null : normalized(String.valueOf(value));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null) return value;
        }
        return null;
    }

    private static String normalized(String value) {
        if (value == null || value.isBlank()) return null;
        return value.strip();
    }

    private static Map<String, String> parse(String value) {
        Map<String, String> result = new HashMap<>();
        if (value == null || value.isBlank()) return Map.of();
        for (String pair : value.split(",")) {
            String[] parts = pair.trim().split("=", 2);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException("invalid audience tenant mapping: " + pair);
            }
            result.put(parts[0], parts[1]);
        }
        return Map.copyOf(result);
    }

    private static Set<String> parseSet(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return java.util.Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(item -> !item.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static void writeProblem(HttpServletResponse response, TenantResolutionException error)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("""
                {"type":"urn:benefit:error:%s","title":"%s","status":403,"detail":"%s","code":"%s"}
                """.formatted(error.code().toLowerCase().replace('_', '-'), error.code(),
                error.getMessage(), error.code()).strip());
    }

    private static final class TenantResolutionException extends RuntimeException {
        private final String code;

        private TenantResolutionException(String code, String message) {
            super(message);
            this.code = code;
        }

        private String code() {
            return code;
        }
    }
}
