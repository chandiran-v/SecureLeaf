package com.secureleaf.auth;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.config.JwtProperties;
import com.secureleaf.auth.dto.AuthPayload;
import com.secureleaf.auth.service.AuthService;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;


/**
 * How the JWT filter answers a bad bearer token. Before this test, an expired or malformed
 * token got an EMPTY HTTP 200: the filter handed the JWT exception to an MVC exception
 * resolver that had no handler for it, so nothing was written and the client's JSON parse
 * failed. Its token-refresh logic never ran, and users saw broken pages instead of
 * "please log in again". See the Phase 1 learning note's "Gotchas" table.
 */
class JwtAuthenticationFilterIT extends AbstractIntegrationTest {

    private static final String ME_QUERY = "{\"query\":\"{ me { id } }\"}";

    @LocalServerPort private int port;
    @Autowired private AuthService authService;
    @Autowired private JwtProperties jwtProperties;

    private String signedToken(String subject, Instant expiresAt) {
        var key = new SecretKeySpec(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        return Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(expiresAt.minusSeconds(900)))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }

    /** Real HTTP against the running server: exactly what a browser receives. */
    private WebTestClient.ResponseSpec postGraphQl(String bearer) {
        var request = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
                .post().uri("/graphql").contentType(MediaType.APPLICATION_JSON).bodyValue(ME_QUERY);
        if (bearer != null) request = request.header("Authorization", "Bearer " + bearer);
        return request.exchange();
    }

    @Test
    void expiredToken_is401_withTokenExpiredCode() {
        String expired = signedToken("1", Instant.now().minusSeconds(60));

        postGraphQl(expired)
                .expectStatus().isUnauthorized()
                .expectHeader().value("WWW-Authenticate", v -> org.assertj.core.api.Assertions.assertThat(v).contains("invalid_token"))
                .expectBody()
                .jsonPath("$.code").isEqualTo("TOKEN_EXPIRED")
                .jsonPath("$.message").isNotEmpty();
    }

    @Test
    void malformedToken_is401_withInvalidTokenCode() {
        postGraphQl("garbage.token.value")
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_TOKEN");
    }

    @Test
    void tokenSignedWithAnotherKey_is401_withInvalidTokenCode() {
        var otherKey = new SecretKeySpec("another-secret-another-secret-another-secret!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        String forged = Jwts.builder().subject("1").expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(otherKey).compact();

        postGraphQl(forged)
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_TOKEN");
    }

    @Test
    void validToken_stillWorks_andNoTokenStillReachesPublicQueries() {
        authService.register("filter@example.com", "Password123!", "Filter Tester");
        AuthPayload login = authService.login("filter@example.com", "Password123!");

        postGraphQl(login.accessToken())
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.me.id").isNotEmpty();

        postGraphQl(null)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.me").isEmpty();
    }
}
