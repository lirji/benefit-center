package com.lrj.benefit.adapters.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CasdoorAuthorityMapperTest {

    private final CasdoorAuthorityMapper mapper = new CasdoorAuthorityMapper();

    @Test
    void mapsCasdoorPermissionObjectsAndKeepsOauthScopes() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-sub")
                .issuedAt(Instant.parse("2026-09-04T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-04T01:00:00Z"))
                .claim("permissions", List.of(
                        Map.of("name", "benefit.admin"),
                        Map.of("name", "benefit.award.read")))
                .claim("scope", "openid profile offline_access")
                .build();

        assertThat(mapper.fromJwt(jwt).stream().map(GrantedAuthority::getAuthority))
                .containsExactly(
                        "benefit.admin",
                        "SCOPE_benefit.admin",
                        "benefit.award.read",
                        "SCOPE_benefit.award.read",
                        "SCOPE_openid",
                        "SCOPE_profile",
                        "SCOPE_offline_access");
    }

    @Test
    void ignoresNonBenefitPermissions() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-sub")
                .issuedAt(Instant.parse("2026-09-04T00:00:00Z"))
                .expiresAt(Instant.parse("2026-09-04T01:00:00Z"))
                .claim("permissions", List.of("recon.read", "benefit.remediate"))
                .build();

        assertThat(mapper.fromJwt(jwt).stream().map(GrantedAuthority::getAuthority))
                .containsExactly("benefit.remediate", "SCOPE_benefit.remediate");
    }
}
