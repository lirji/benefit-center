package com.lrj.benefit.adapters.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 将统一 Casdoor 的 permissions/scope claim 映射为本地 API authority。
 * Casdoor 业务能力在 {@code permissions}（对象 {@code {"name":"benefit.admin"}} 或字符串），
 * 不在 OIDC {@code scope}。matcher 同时认 {@code benefit.*} 与 {@code SCOPE_benefit.*}。
 */
public final class CasdoorAuthorityMapper {

    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::fromJwt);
        converter.setPrincipalClaimName("sub");
        return converter;
    }

    Collection<GrantedAuthority> fromJwt(Jwt jwt) {
        Set<GrantedAuthority> mapped = new LinkedHashSet<>();
        addPermissions(mapped, jwt.getClaims().get("permissions"));
        addScope(mapped, jwt.getClaim("scope"));
        addScope(mapped, jwt.getClaim("scp"));
        return mapped;
    }

    private void addPermissions(Set<GrantedAuthority> mapped, Object claim) {
        if (claim instanceof Collection<?> values) {
            values.forEach(value -> addPermission(mapped, value instanceof Map<?, ?> item ? item.get("name") : value));
        } else {
            addPermission(mapped, claim);
        }
    }

    private void addPermission(Set<GrantedAuthority> mapped, Object value) {
        if (value == null) return;
        String permission = String.valueOf(value).trim();
        if (permission.isEmpty() || !permission.startsWith("benefit.")) return;
        mapped.add(new SimpleGrantedAuthority(permission));
        mapped.add(new SimpleGrantedAuthority("SCOPE_" + permission));
    }

    private void addScope(Set<GrantedAuthority> mapped, Object claim) {
        if (claim instanceof Collection<?> values) {
            values.forEach(value -> addOauthScope(mapped, value));
        } else if (claim instanceof String value) {
            for (String scope : value.split(" ")) addOauthScope(mapped, scope);
        }
    }

    private void addOauthScope(Set<GrantedAuthority> mapped, Object value) {
        if (value == null) return;
        String scope = String.valueOf(value).trim();
        if (!scope.isEmpty()) mapped.add(new SimpleGrantedAuthority("SCOPE_" + scope));
    }
}
