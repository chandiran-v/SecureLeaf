package com.secureleaf.auth.service;

import com.secureleaf.auth.entity.AuthProvider;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.RefreshTokenRepository;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.common.config.AppProperties;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test — no Spring context, no database. Same style as
 * {@code TileUrlSignerTest}: {@link PasswordResetService} takes an injectable {@link Clock}
 * specifically so this expiry check can fix "now" instead of racing the real clock (acceptance
 * criterion 7: "fails after expiry (using a Clock)").
 */
class PasswordResetServiceTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final String RAW_TOKEN = "unit-test-raw-token";

    private UserRepository userRepository;
    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        jwtService = mock(JwtService.class);
        when(jwtService.hashToken(RAW_TOKEN)).thenReturn("hashed-token");
    }

    @Test
    void resetPassword_afterExpiry_throwsInvalidToken() {
        User user = userWithResetToken(ISSUED_AT.plus(java.time.Duration.ofMinutes(30)));
        when(userRepository.findByResetTokenHash("hashed-token")).thenReturn(Optional.of(user));

        // "Now" is one second past the token's expiry.
        Clock later = Clock.fixed(ISSUED_AT.plus(java.time.Duration.ofMinutes(30)).plusSeconds(1), ZoneOffset.UTC);
        PasswordResetService service = serviceWithClock(later);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resetPassword(RAW_TOKEN, "a-brand-new-password"));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    @Test
    void resetPassword_beforeExpiry_succeeds() {
        User user = userWithResetToken(ISSUED_AT.plus(java.time.Duration.ofMinutes(30)));
        when(userRepository.findByResetTokenHash("hashed-token")).thenReturn(Optional.of(user));

        // "Now" is one second before the token's expiry.
        Clock earlier = Clock.fixed(ISSUED_AT.plus(java.time.Duration.ofMinutes(30)).minusSeconds(1), ZoneOffset.UTC);
        PasswordResetService service = serviceWithClock(earlier);

        boolean result = service.resetPassword(RAW_TOKEN, "a-brand-new-password");

        assertThat(result).isTrue();
        assertThat(user.getResetTokenHash()).isNull(); // single-use — cleared on success
        assertThat(user.getResetTokenExpiresAt()).isNull();
    }

    @Test
    void resetPassword_withUnknownToken_throwsInvalidToken() {
        when(userRepository.findByResetTokenHash(any())).thenReturn(Optional.empty());
        PasswordResetService service = serviceWithClock(Clock.fixed(ISSUED_AT, ZoneOffset.UTC));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resetPassword(RAW_TOKEN, "a-brand-new-password"));
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_TOKEN);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User userWithResetToken(Instant expiresAt) {
        User user = new User();
        user.setEmail("reset-me@example.com");
        user.setDisplayName("Reset Me");
        user.setAuthProvider(AuthProvider.LOCAL);
        user.setPasswordHash(new BCryptPasswordEncoder().encode("original-password"));
        user.setResetTokenHash("hashed-token");
        user.setResetTokenExpiresAt(expiresAt);
        return user;
    }

    private PasswordResetService serviceWithClock(Clock clock) {
        return new PasswordResetService(
                userRepository,
                mock(RefreshTokenRepository.class),
                mock(StringRedisTemplate.class),
                new BCryptPasswordEncoder(),
                jwtService,
                new AppProperties("http://localhost:5173"),
                mock(ApplicationEventPublisher.class),
                clock);
    }
}
