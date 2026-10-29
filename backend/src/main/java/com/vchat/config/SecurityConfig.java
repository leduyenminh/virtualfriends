package com.vchat.config;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import java.util.List;

/**
 * Spring Security setup:
 *  - Access: short-lived JWT in the Authorization header (REST) or the STOMP CONNECT frame (WebSocket).
 *  - Sessions: rotating refresh token in an httpOnly cookie, only accepted by /api/auth/refresh and /logout.
 *  - Optional OAuth2 login with Google and/or Facebook, ending in the same refresh-cookie session.
 *  - Roles: USER and ADMIN. URL rules below + @PreAuthorize on admin endpoints (defence in depth).
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtFilter jwtFilter,
                                    ObjectProvider<ClientRegistrationRepository> oauthClients,
                                    OAuthLoginHandler oauthHandler) throws Exception {
        http
                // CSRF tokens aren't needed: API calls authenticate with a header that other sites can't set, and the
                // only cookie (refresh) is SameSite=Strict, path-scoped and Origin-checked in AuthController.
                .csrf(c -> c.disable())
                .cors(c -> {})
                // STATELESS: no SecurityContext in the session. The OAuth2 flow still uses a short-lived session
                // only to remember the "state" parameter between the redirect and the callback.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // Error dispatches (Tomcat forwarding to /error) are internal: don't re-authorize them, or a
                        // stateless 403 turns into a misleading 401 on the way out.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/auth/**", "/ws/**", "/files/**", "/actuator/health/**",
                                "/oauth2/**", "/login/oauth2/**").permitAll()
                        // API docs + GraphiQL (only exist when API_DOCS_ENABLED=true; data calls still need a token)
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html", "/graphiql/**",
                                "/graphql/schema").permitAll()
                        .requestMatchers("/api/admin/**", "/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> {                                 // not signed in
                            res.setStatus(HttpStatus.UNAUTHORIZED.value());
                            res.setContentType("application/json");
                            res.getWriter().write("{\"error\":\"Sign in required (missing, expired or revoked access token)\"}");
                        })
                        .accessDeniedHandler((req, res, ex) -> {                                       // signed in, wrong role
                            res.setStatus(HttpStatus.FORBIDDEN.value());
                            res.setContentType("application/json");
                            res.getWriter().write("{\"error\":\"You don't have permission to do that\"}");
                        }))
                .headers(h -> h
                        .frameOptions(f -> f.sameOrigin())
                        .contentTypeOptions(c -> {})
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.SAME_ORIGIN)))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        if (oauthClients.getIfAvailable() != null) {
            http.oauth2Login(o -> o.successHandler(oauthHandler).failureHandler(oauthHandler));
        }
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    CorsConfigurationSource corsConfigurationSource(VchatProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(props.corsOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("X-Request-Id"));
        cfg.setAllowCredentials(true); // only matters if the SPA is served from another origin than the API
        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }
}
