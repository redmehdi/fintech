package com.eurmad.api.security;

import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OIDC bearer-token resource server (FX-004).
 *
 * <p>Fail-closed authorization: customer APIs require a valid token carrying {@link
 * #CUSTOMER_SCOPE}; admin and webhook routes are denied until their tickets add RBAC and signature
 * verification; everything not listed is denied. Role or group claims in tokens are never mapped
 * to authorities.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    /** OAuth2 scope required for every customer-facing {@code /v1} operation. */
    public static final String CUSTOMER_SCOPE = "eurmad.customer";

    static final String CUSTOMER_AUTHORITY = "SCOPE_" + CUSTOMER_SCOPE;

    @Bean
    JwtDecoder jwtDecoder(OidcProperties properties) {
        return OidcJwtDecoders.create(properties);
    }

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        ProblemSecurityHandlers handlers = new ProblemSecurityHandlers(mapper);
        http.csrf(AbstractHttpConfigurer::disable) // bearer tokens only; no cookie authentication
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/v1/admin/**", "/v1/webhooks/**").denyAll()
                        .requestMatchers("/v1/**").hasAuthority(CUSTOMER_AUTHORITY)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(handlers)
                        .accessDeniedHandler(handlers));
        return http.build();
    }
}
