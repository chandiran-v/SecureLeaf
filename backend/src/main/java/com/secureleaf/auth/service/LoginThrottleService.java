package com.secureleaf.auth.service;

import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Phase 9, D5 — a small, targeted guard against credential stuffing: at most
 * {@value #MAX_FAILED_ATTEMPTS} failed logins for one email+IP pair per {@value #WINDOW_MINUTES}
 * minutes. This is deliberately not the general-purpose Bucket4j rate limiter planned for MVP2
 * (Phase 11) — it only ever looks at *login failures*, and only exists to slow down a script
 * trying passwords against one account from one source, not to shape traffic generally.
 *
 * WHY EMAIL+IP, NOT EMAIL ALONE OR IP ALONE
 * Keying on email alone would let one attacker lock a *victim* out of their own account by
 * deliberately failing five logins against it from a different IP than the victim's — a denial-of-
 * service against real users, using the anti-abuse feature itself as the weapon. Keying on IP
 * alone is defeated by an attacker rotating IPs, and separately over-punishes a shared IP (campus
 * NAT, corporate proxy) where one person's typos lock everyone behind it out. Email+IP narrows the
 * counter to "this specific attacker, guessing this specific account" — the actual threat.
 *
 * WHY THE SAME REDIS INCR+EXPIRE PATTERN AS PasswordResetService
 * Same shape, same reasoning: the first failure in a window creates the counter with a TTL, every
 * later failure in that window just increments it, and once the cap is exceeded the counter is
 * left alone (not reset) so the limit holds for the rest of the window rather than restarting on
 * every subsequent attempt.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginThrottleService {

    static final int MAX_FAILED_ATTEMPTS = 5;
    static final int WINDOW_MINUTES = 15;
    private static final Duration WINDOW = Duration.ofMinutes(WINDOW_MINUTES);
    private static final String KEY_PREFIX = "login:rl:";

    private final StringRedisTemplate redisTemplate;

    /** Called before even checking the password — a caller already over the limit never gets a
     *  timing or error-message difference to learn from; they just get RATE_LIMITED outright. */
    public void assertNotRateLimited(String normalizedEmail, String ipAddress) {
        String key = key(normalizedEmail, ipAddress);
        String count = redisTemplate.opsForValue().get(key);
        if (count != null && Integer.parseInt(count) >= MAX_FAILED_ATTEMPTS) {
            log.warn("Login rate-limited for email+IP pair (too many recent failures)");
            throw new BusinessException(ErrorCode.RATE_LIMITED,
                    "Too many failed login attempts. Please try again in a few minutes.");
        }
    }

    /** Called on every failed password/credential check. */
    public void recordFailedAttempt(String normalizedEmail, String ipAddress) {
        String key = key(normalizedEmail, ipAddress);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
    }

    /** Called on a successful login — a legitimate sign-in clears the slate for this pair. */
    public void resetOnSuccess(String normalizedEmail, String ipAddress) {
        redisTemplate.delete(key(normalizedEmail, ipAddress));
    }

    private static String key(String normalizedEmail, String ipAddress) {
        return KEY_PREFIX + normalizedEmail + ":" + (ipAddress == null ? "unknown" : ipAddress);
    }
}
