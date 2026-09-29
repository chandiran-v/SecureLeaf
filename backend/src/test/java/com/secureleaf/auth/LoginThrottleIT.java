package com.secureleaf.auth;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.service.AuthService;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase 9, D5 — at most 5 failed logins per email+IP per 15 minutes; the 6th gets RATE_LIMITED;
 * a successful login resets the counter. Acceptance criterion 4.
 */
class LoginThrottleIT extends AbstractIntegrationTest {

    private static final String EMAIL = "throttle-target@example.com";
    private static final String PASSWORD = "CorrectHorse123!";
    private static final String IP = "203.0.113.7";

    @Autowired
    private AuthService authService;

    @Test
    void sixthFailedLogin_isRateLimited_thenSuccessResetsCounter() {
        authService.register(EMAIL, PASSWORD, "Throttle Target");

        // Attempts 1-5: wrong password, ordinary INVALID_CREDENTIALS each time.
        for (int attempt = 1; attempt <= 5; attempt++) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> authService.login(EMAIL, "wrong-password", IP));
            assertThat(ex.getErrorCode()).as("attempt %d", attempt).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        // Attempt 6: still wrong, but now the caller doesn't even get a credentials check —
        // it's rejected outright as RATE_LIMITED.
        BusinessException sixth = assertThrows(BusinessException.class,
                () -> authService.login(EMAIL, "wrong-password", IP));
        assertThat(sixth.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);

        // Even the CORRECT password is rejected while the window is active — the limit blocks
        // the pair outright, it doesn't just keep counting failures.
        BusinessException stillLimited = assertThrows(BusinessException.class,
                () -> authService.login(EMAIL, PASSWORD, IP));
        assertThat(stillLimited.getErrorCode()).isEqualTo(ErrorCode.RATE_LIMITED);
    }

    @Test
    void successfulLogin_resetsCounter_soSubsequentFailuresStartFresh() {
        authService.register(EMAIL, PASSWORD, "Throttle Target");

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThrows(BusinessException.class, () -> authService.login(EMAIL, "wrong-password", IP));
        }

        // One correct login, one attempt short of the 5-failure cap.
        authService.login(EMAIL, PASSWORD, IP);

        // The counter reset on success, so 4 more failures right after should NOT trip the limit —
        // if the counter hadn't reset, this 5th cumulative-looking failure would be the tripwire.
        for (int attempt = 1; attempt <= 4; attempt++) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> authService.login(EMAIL, "wrong-password", IP));
            assertThat(ex.getErrorCode()).as("post-reset attempt %d", attempt).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }
    }

    @Test
    void differentIp_getsItsOwnCounter() {
        authService.register(EMAIL, PASSWORD, "Throttle Target");

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThrows(BusinessException.class, () -> authService.login(EMAIL, "wrong-password", IP));
        }
        assertThrows(BusinessException.class, () -> authService.login(EMAIL, "wrong-password", IP));

        // A different source IP guessing the same email has its own, unaffected counter — this is
        // exactly why the key is email+IP, not email alone (see LoginThrottleService's javadoc).
        BusinessException fromOtherIp = assertThrows(BusinessException.class,
                () -> authService.login(EMAIL, "wrong-password", "198.51.100.9"));
        assertThat(fromOtherIp.getErrorCode()).isEqualTo(ErrorCode.INVALID_CREDENTIALS);
    }
}
