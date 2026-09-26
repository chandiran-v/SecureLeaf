package com.secureleaf.auth;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.AuthProvider;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.RefreshTokenRepository;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * AUTH-07 — enumeration-safe password reset. See {@code PasswordResetServiceTest} for the
 * pure-unit Clock-based expiry check; this IT drives the real GraphQL mutations end to end
 * against Postgres + Redis, with {@link RecordingMailSender} standing in for SMTP.
 */
class PasswordResetIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RecordingMailSender mailSender;

    private User localUser;
    private User googleUser;
    private HttpGraphQlTester anonymous;

    private static final String REQUEST_RESET = """
            mutation($email: String!) { requestPasswordReset(email: $email) }
            """;

    private static final String RESET_PASSWORD = """
            mutation($token: String!, $newPassword: String!) { resetPassword(token: $token, newPassword: $newPassword) }
            """;

    @BeforeEach
    void setUp() {
        mailSender.clear();

        localUser = new User();
        localUser.setEmail("reset-me@example.com");
        localUser.setDisplayName("Reset Me");
        localUser.setPasswordHash(new BCryptPasswordEncoder().encode("original-password"));
        localUser.setAuthProvider(AuthProvider.LOCAL);
        UserRole role = new UserRole();
        role.setUser(localUser);
        role.setRole(Role.BUYER);
        localUser.getRoles().add(role);
        localUser = userRepository.save(localUser);

        googleUser = new User();
        googleUser.setEmail("google-user@example.com");
        googleUser.setDisplayName("Google User");
        googleUser.setAuthProvider(AuthProvider.GOOGLE);
        googleUser.setGoogleSub("google-sub-123");
        googleUser = userRepository.save(googleUser);

        anonymous = HttpGraphQlTester.create(
                WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/graphql").build());
    }

    // ── D8 — request: enumeration-safe, rate-limited ─────────────────────────

    @Test
    void unknownEmail_returnsTrue_andSendsNothing() {
        anonymous.document(REQUEST_RESET).variable("email", "nobody@example.com")
                .execute().path("requestPasswordReset").entity(Boolean.class).isEqualTo(true);

        assertThat(mailSender.sent()).isEmpty();
    }

    @Test
    void knownLocalEmail_sendsExactlyOneEmail_withAResetLink() {
        anonymous.document(REQUEST_RESET).variable("email", localUser.getEmail())
                .execute().path("requestPasswordReset").entity(Boolean.class).isEqualTo(true);

        awaitEmailCount(1); // @Async + AFTER_COMMIT — the email lands shortly after the response
        assertThat(mailSender.sent().get(0).getTo()).containsExactly(localUser.getEmail());
        assertThat(mailSender.sent().get(0).getText()).contains("/reset-password?token=");

        User reloaded = userRepository.findById(localUser.getId()).orElseThrow();
        assertThat(reloaded.getResetTokenHash()).isNotBlank();
        assertThat(reloaded.getResetTokenExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void googleOnlyAccount_getsATellMeAboutGoogleEmail_notAResetLink() {
        anonymous.document(REQUEST_RESET).variable("email", googleUser.getEmail())
                .execute().path("requestPasswordReset").entity(Boolean.class).isEqualTo(true);

        awaitEmailCount(1);
        assertThat(mailSender.sent().get(0).getSubject()).containsIgnoringCase("google");
        assertThat(mailSender.sent().get(0).getText()).doesNotContain("/reset-password?token=");

        assertThat(userRepository.findById(googleUser.getId()).orElseThrow().getResetTokenHash()).isNull();
    }

    @Test
    void stopsSendingAfterThreeRequestsPerHour_butStillReturnsTrue() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            anonymous.document(REQUEST_RESET).variable("email", localUser.getEmail())
                    .execute().path("requestPasswordReset").entity(Boolean.class).isEqualTo(true);
        }
        awaitEmailCount(3);

        anonymous.document(REQUEST_RESET).variable("email", localUser.getEmail())
                .execute().path("requestPasswordReset").entity(Boolean.class).isEqualTo(true);

        // Give a wrongly-sent 4th email time to show up, then confirm it never does.
        Thread.sleep(500);
        assertThat(mailSender.sent()).hasSize(3);
    }

    // ── D9 — reset ────────────────────────────────────────────────────────────

    @Test
    void resetPassword_succeedsOnce_thenRejectsReuse_andRevokesOldSessions() {
        String refreshToken = jwtService.generateRefreshToken();
        var rt = new com.secureleaf.auth.entity.RefreshToken();
        rt.setUser(localUser);
        rt.setTokenHash(jwtService.hashToken(refreshToken));
        rt.setExpiresAt(Instant.now().plusSeconds(3600));
        refreshTokenRepository.save(rt);

        anonymous.document(REQUEST_RESET).variable("email", localUser.getEmail()).execute();
        awaitEmailCount(1);
        String rawToken = extractToken(mailSender.sent().get(0).getText());

        anonymous.document(RESET_PASSWORD).variable("token", rawToken).variable("newPassword", "brand-new-password")
                .execute().path("resetPassword").entity(Boolean.class).isEqualTo(true);

        // Single-use — the same token fails the second time.
        anonymous.document(RESET_PASSWORD).variable("token", rawToken).variable("newPassword", "another-password12")
                .execute().errors().expect(e -> "INVALID_TOKEN".equals(e.getExtensions().get("code"))).verify();

        // Old password no longer works; the new one does (checked via the login mutation).
        String loginQuery = """
                mutation($input: LoginInput!) { login(input: $input) { accessToken } }
                """;
        anonymous.document(loginQuery)
                .variable("input", Map.of("email", localUser.getEmail(), "password", "original-password"))
                .execute().errors().expect(e -> "INVALID_CREDENTIALS".equals(e.getExtensions().get("code"))).verify();
        anonymous.document(loginQuery)
                .variable("input", Map.of("email", localUser.getEmail(), "password", "brand-new-password"))
                .execute().path("login.accessToken").entity(String.class).satisfies(t -> assertThat(t).isNotBlank());

        // The refresh token issued before the reset is revoked. (Read via jdbcTemplate, not
        // RefreshTokenRepository.findByTokenHashForUpdate — that method takes a pessimistic
        // lock, which requires an active transaction; this plain test method has none.)
        Boolean revoked = jdbcTemplate.queryForObject(
                "SELECT revoked_at IS NOT NULL FROM refresh_tokens WHERE token_hash = ?",
                Boolean.class, jwtService.hashToken(refreshToken));
        assertThat(revoked).isTrue();
    }

    @Test
    void resetPassword_failsAfterExpiry() {
        anonymous.document(REQUEST_RESET).variable("email", localUser.getEmail()).execute();
        awaitEmailCount(1);
        String rawToken = extractToken(mailSender.sent().get(0).getText());

        jdbcTemplate.update("UPDATE users SET reset_token_expires_at = now() - interval '1 minute' WHERE id = ?",
                localUser.getId());

        anonymous.document(RESET_PASSWORD).variable("token", rawToken).variable("newPassword", "brand-new-password")
                .execute().errors().expect(e -> "INVALID_TOKEN".equals(e.getExtensions().get("code"))).verify();
    }

    @Test
    void resetPassword_withUnknownToken_isInvalidToken() {
        anonymous.document(RESET_PASSWORD).variable("token", "not-a-real-token").variable("newPassword", "brand-new-password")
                .execute().errors().expect(e -> "INVALID_TOKEN".equals(e.getExtensions().get("code"))).verify();
    }

    private void awaitEmailCount(int expected) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(mailSender.sent()).hasSize(expected));
    }

    private static String extractToken(String body) {
        int idx = body.indexOf("token=");
        assertThat(idx).isPositive();
        String rest = body.substring(idx + "token=".length());
        return rest.split("[\\s\\n]", 2)[0].trim();
    }
}
