package com.secureleaf.auth.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Central Spring Security configuration for SecureLeaf.
 *
 * <h3>Key decisions</h3>
 * <ul>
 *   <li><b>Stateless sessions</b> — JWT-based auth, no HTTP sessions.</li>
 *   <li><b>CSRF disabled</b> — safe for stateless APIs that rely on Bearer tokens.</li>
 *   <li><b>GraphQL endpoint is public at HTTP level</b> — auth is enforced at the
 *       resolver level via {@code @PreAuthorize}, because Spring for GraphQL needs
 *       the endpoint accessible to process both authenticated and unauthenticated
 *       queries (e.g. {@code products}, {@code login}, {@code register}).</li>
 *   <li><b>{@code @EnableMethodSecurity}</b> enables {@code @PreAuthorize} on
 *       resolver methods for fine-grained access control.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // GraphQL endpoint — public at HTTP level; auth enforced at resolver level
                        .requestMatchers("/graphql", "/graphiql/**").permitAll()
                        // REST auth endpoints (reserved for file upload etc.)
                        .requestMatchers("/api/auth/**").permitAll()
                        // Free-preview page images — public, no buyer identity required.
                        // Access control (LIVE status + page-range) lives in PreviewService.
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/products/*/preview/*").permitAll()
                        // Payment webhooks — the caller is Razorpay's server, which has no JWT.
                        // Authenticity is the HMAC signature, checked in RazorpayWebhookController.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/webhooks/**").permitAll()
                        // Mock gateway ("Razorpay's servers" stand-in). The controller only exists
                        // when payment.gateway.provider=mock; CommerceConfig blocks that in prod.
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/mock-gateway/**").permitAll()
                        // Actuator health & info
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        // D13 — the DRM tile endpoint is GET-only and always requires a JWT. The rest
                        // of D6's checks (signature, session, entitlement, page range) live inside
                        // SecureTileService, not here — this line only rules out anonymous access and
                        // any verb other than GET, so a POST/DELETE to the same path 401/403s before
                        // it ever reaches the controller.
                        .requestMatchers(HttpMethod.GET, "/api/viewer/**").authenticated()
                        .requestMatchers("/api/viewer/**").denyAll()
                        // Everything else requires authentication
                        .anyRequest().authenticated()
                )
                .exceptionHandling(handling -> handling
                        // D6 step 1 / VIEW acceptance criterion 12: no JWT at all is 401 ("who are
                        // you"), distinct from a valid JWT that fails an authorization check (403,
                        // handled by BusinessException -> GlobalRestExceptionHandler below).
                        .authenticationEntryPoint(new HttpStatusEntryPoint(org.springframework.http.HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // Disable Spring's default OAuth2 login (we handle Google OAuth via GraphQL mutation)
                .oauth2Login(oauth2 -> oauth2.disable())
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:5173", "http://localhost:3000"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
