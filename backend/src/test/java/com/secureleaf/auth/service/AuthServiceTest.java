package com.secureleaf.auth.service;

import com.secureleaf.admin.service.AdminBootstrapService;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.RefreshTokenRepository;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.repository.UserRoleRepository;
import com.secureleaf.creator.repository.CreatorProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test — no Spring context. Phase 8, D1: {@code register()} must hand every new user
 * to {@link AdminBootstrapService#grantAdminRoleIfConfigured}, which is what makes a registration
 * with a configured {@code ADMIN_EMAILS} address ADMIN immediately rather than only after the
 * next restart. The config-matching logic itself (case-insensitivity, idempotency) is covered by
 * {@code AdminBootstrapServiceTest} — this test only proves the wiring: register() calls it,
 * exactly once, with the newly-created user.
 */
class AuthServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);
    private final AdminBootstrapService adminBootstrapService = mock(AdminBootstrapService.class);

    @Test
    void register_handsTheNewUserToAdminBootstrapService() {
        when(userRepository.existsByEmail(any())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthService authService = new AuthService(
                userRepository,
                mock(UserRoleRepository.class),
                mock(RefreshTokenRepository.class),
                mock(CreatorProfileRepository.class),
                mock(JwtService.class),
                mock(PasswordEncoder.class),
                mock(GoogleOAuthService.class),
                adminBootstrapService);

        User registered = authService.register("new-user@example.com", "Password123!", "New User");

        verify(adminBootstrapService).grantAdminRoleIfConfigured(registered);
    }
}
