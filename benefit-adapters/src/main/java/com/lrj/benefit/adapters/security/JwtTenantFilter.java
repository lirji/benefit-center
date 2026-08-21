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

public final class JwtTenantFilter extends OncePerRequestFilter {
    private final boolean devMode;
    private final Map<String, String> audienceTenants;

    public JwtTenantFilter(boolean devMode, String audienceTenantMappings) {
        this.devMode = devMode;
        this.audienceTenants = parse(audienceTenantMappings);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        try {
            String tenant = resolveTenant(request);
            if (tenant != null) TenantContext.set(tenant);
            chain.doFilter(request, response);
        } catch (IllegalArgumentException invalidTenant) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, invalidTenant.getMessage());
        } finally {
            TenantContext.clear();
        }
    }

    private String resolveTenant(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String tenant = null;
        if (authentication instanceof JwtAuthenticationToken token) tenant = tenantFromJwt(token.getToken());
        if (tenant == null && devMode) tenant = request.getHeader("X-Tenant-Id");
        String asserted = request.getHeader("X-Tenant-Id");
        if (!devMode && asserted != null && tenant != null && !tenant.equals(asserted)) {
            throw new IllegalArgumentException("tenant header does not match the signed JWT audience");
        }
        return tenant;
    }

    private String tenantFromJwt(Jwt jwt) {
        for (String audience : jwt.getAudience()) {
            String tenant = audienceTenants.get(audience);
            if (tenant != null) return tenant;
        }
        throw new IllegalArgumentException("JWT audience is not mapped to a benefit tenant");
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
}
