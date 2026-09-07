package com.lrj.benefit.adapters.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

public final class BenefitSecurityConfig {
    private BenefitSecurityConfig() {}

    @Configuration
    static class PortalCors {
        /** 仅开放门户探活跨域 GET；浏览器入口已迁到独立 console，不再开放 Java GET /。 */
        @Bean CorsConfigurationSource corsConfigurationSource() {
            CorsConfiguration config = new CorsConfiguration();
            config.setAllowedOrigins(List.of("*"));
            config.setAllowedMethods(List.of("GET", "HEAD", "OPTIONS"));
            config.setAllowedHeaders(List.of("*"));
            config.setAllowCredentials(false);
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/healthz", config);
            return source;
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "benefit.security.dev-mode", havingValue = "true")
    static class Dev {
        @Bean JwtTenantFilter jwtTenantFilter(
                @Value("${benefit.security.audience-tenants:}") String mappings) {
            return new JwtTenantFilter(true, mappings);
        }

        @Bean SecurityFilterChain devBenefitSecurity(HttpSecurity http, JwtTenantFilter tenantFilter) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .cors(Customizer.withDefaults())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class)
                    .build();
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "benefit.security.dev-mode", havingValue = "false", matchIfMissing = true)
    static class Secure {
        @Bean JwtDecoder benefitJwtDecoder(
                @Value("${benefit.security.jwk-set-uri:http://localhost:8000/.well-known/jwks}") String uri) {
            return NimbusJwtDecoder.withJwkSetUri(uri).build();
        }

        @Bean CasdoorAuthorityMapper casdoorAuthorityMapper() {
            return new CasdoorAuthorityMapper();
        }

        @Bean JwtTenantFilter jwtTenantFilter(
                @Value("${benefit.security.audience-tenants:}") String mappings,
                @Value("${benefit.security.delegated-tenant-subjects:}") String delegatedTenantSubjects) {
            return new JwtTenantFilter(false, mappings, delegatedTenantSubjects);
        }

        @Bean SecurityFilterChain secureBenefitSecurity(HttpSecurity http, JwtTenantFilter tenantFilter,
                                                        CasdoorAuthorityMapper authorityMapper) throws Exception {
            return http.csrf(csrf -> csrf.disable())
                    .cors(Customizer.withDefaults())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(auth -> auth
                            .requestMatchers("/healthz", "/actuator/health/**", "/actuator/info").permitAll()
                            .requestMatchers(HttpMethod.OPTIONS, "/healthz").permitAll()
                            .requestMatchers(HttpMethod.POST, "/openapi/v1/award-orders").hasAuthority("SCOPE_benefit.award.write")
                            .requestMatchers(HttpMethod.GET, "/openapi/v1/award-orders/**").hasAuthority("SCOPE_benefit.award.read")
                            .requestMatchers(HttpMethod.GET, "/internal/v1/catalog/skus")
                            .hasAuthority("SCOPE_benefit.catalog.read")
                            .requestMatchers(HttpMethod.POST, "/openapi/v1/wallet-entries/**").hasAuthority("SCOPE_benefit.admin")
                            .requestMatchers("/internal/v1/remediations/**").hasAuthority("SCOPE_benefit.remediate")
                            .requestMatchers("/admin/v1/**").hasAuthority("SCOPE_benefit.admin")
                            .requestMatchers("/callbacks/v1/**").permitAll()
                            .anyRequest().authenticated())
                    .oauth2ResourceServer(resource -> resource.jwt(jwt ->
                            jwt.jwtAuthenticationConverter(authorityMapper.jwtAuthenticationConverter())))
                    .addFilterAfter(tenantFilter, BearerTokenAuthenticationFilter.class)
                    .build();
        }
    }
}
