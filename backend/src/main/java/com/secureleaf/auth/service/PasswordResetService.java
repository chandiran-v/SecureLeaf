package com.secureleaf.auth.service;

import com.secureleaf.auth.entity.AuthProvider;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.RefreshTokenRepository;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.common.config.AppProperties;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Enumeration-safe password reset (AUTH-07).
 *
 * WHY "ALWAYS RETURN TRUE" (D8) — if {@code requestPasswordReset} returned false for an
 * unknown email and true for a known one, an attacker could feed it a list of addresses and
 * learn which ones have SecureLeaf accounts, one HTTP response at a time. Returning {@code true}
 * unconditionally — and taking exactly the same amount of externally-visible action (a DB read,
 * nothing else) for the unknown case — means the response carries no signal either way. The
 * timing difference between "hit the DB and stop" and "hit the DB, write a token, publish an
 * event" is a much narrower side channel than a boolean, and closing it fully (e.g. a dummy
 * BCrypt hash for unknown emails, matching login's constant-time posture) is the honest gap this
 * design accepts for MVP — see the learning note.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordResetService {

    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    private static final int MAX_REQUESTS_PER_HOUR = 3;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofHours(1);
    private static final String RATE_LIMIT_KEY_PREFIX = "pwreset:rl:";
    private static final int MIN_PASSWORD_LENGTH = 10;
    private static final int MAX_PASSWORD_LENGTH = 100;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final StringRedisTemplate redisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties appProperties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    // ── Request ──────────────────────────────────────────────────────────────

    @Transactional
    public boolean requestPasswordReset(String email) {
        String normalizedEmail = AuthService.normalizeEmail(email);
        Optional<User> maybeUser = userRepository.findByEmail(normalizedEmail);
        if (maybeUser.isEmpty()) {
            log.info("Password reset requested for an email with no account — no-op (enumeration-safe)");
            return true;
        }

        if (!withinRateLimit(normalizedEmail)) {
            log.info("Password reset rate-limited for {}", normalizedEmail);
            return true;
        }

        User user = maybeUser.get();
        if (user.getAuthProvider() != AuthProvider.LOCAL) {
            eventPublisher.publishEvent(googleOnlyEmail(user));
            return true;
        }

        String rawToken = generateToken();
        user.setResetTokenHash(jwtService.hashToken(rawToken));
        user.setResetTokenExpiresAt(Instant.now(clock).plus(TOKEN_TTL));
        userRepository.save(user);

        eventPublisher.publishEvent(resetLinkEmail(user, rawToken));
        log.info("Password reset token issued for user id={}", user.getId());
        return true;
    }

    // ── Reset ────────────────────────────────────────────────────────────────

    @Transactional
    public boolean resetPassword(String rawToken, String newPassword) {
        validatePassword(newPassword);

        User user = userRepository.findByResetTokenHash(jwtService.hashToken(rawToken))
                .filter(u -> u.getResetTokenExpiresAt() != null && u.getResetTokenExpiresAt().isAfter(Instant.now(clock)))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_TOKEN, "This reset link is invalid or has expired."));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        // Single-use (D9): clear the token so this same link can never be replayed.
        user.setResetTokenHash(null);
        user.setResetTokenExpiresAt(null);
        userRepository.save(user);

        // A stolen refresh token must die with the old password it was issued under.
        refreshTokenRepository.revokeAllForUser(user.getId(), Instant.now(clock));

        log.info("Password reset completed for user id={}", user.getId());
        return true;
    }

    // ── Rate limiting (D8) ───────────────────────────────────────────────────

    /**
     * Redis INCR + EXPIRE: the first request in a window creates the key with a 1-hour TTL: the
     * TTL is set right after the key is created, so a heavy burst can never leave it without an
     * expiry. Every request within that hour increments the same counter; once it exceeds the
     * cap, this returns false without deleting the counter, so the limit holds for the rest of
     * the hour rather than resetting on the next request.
     */
    private boolean withinRateLimit(String normalizedEmail) {
        String key = RATE_LIMIT_KEY_PREFIX + normalizedEmail;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, RATE_LIMIT_WINDOW);
        }
        return count != null && count <= MAX_REQUESTS_PER_HOUR;
    }

    // ── Email content ────────────────────────────────────────────────────────

    private PasswordResetMailRequestedEvent resetLinkEmail(User user, String rawToken) {
        String link = appProperties.frontendBaseUrl() + "/reset-password?token=" + rawToken;
        String body = """
                We received a request to reset your SecureLeaf password.

                Click the link below to choose a new one. This link expires in 30 minutes and can only be used once:

                %s

                If you didn't request this, you can safely ignore this email — your password will not change.
                """.formatted(link);
        return new PasswordResetMailRequestedEvent(user.getEmail(), "Reset your SecureLeaf password", body);
    }

    private PasswordResetMailRequestedEvent googleOnlyEmail(User user) {
        String body = """
                We received a request to reset the password for your SecureLeaf account.

                This account signs in with Google, so it has no SecureLeaf password to reset. Use the
                "Continue with Google" button on the login page instead.

                If you didn't request this, you can safely ignore this email.
                """;
        return new PasswordResetMailRequestedEvent(user.getEmail(), "You sign in with Google", body);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void validatePassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "Password must be between " + MIN_PASSWORD_LENGTH + " and " + MAX_PASSWORD_LENGTH + " characters.");
        }
    }

    /** 256 bits from SecureRandom, Base64URL-encoded — same recipe as a viewer session token. */
    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
