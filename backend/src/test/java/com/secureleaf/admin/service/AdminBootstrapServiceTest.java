package com.secureleaf.admin.service;

import com.secureleaf.admin.AdminProperties;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test — no Spring context, no database (same style as {@code PasswordResetServiceTest}
 * and {@code TileUrlSignerTest}). Phase 8 acceptance criterion 2: "ADMIN_EMAILS bootstrap grants
 * ADMIN, and a second start doesn't duplicate it."
 *
 * An earlier version of this test spun up a SECOND Spring context (via
 * {@code @DynamicPropertySource} on {@code admin.emails}) to exercise the real
 * {@code ApplicationRunner} end to end. That context and its own {@code @Scheduled}
 * {@code ProcessingJobWorker} poller ran concurrently with the suite's default cached context
 * against the SAME shared Testcontainers Postgres/Redis (see AbstractIntegrationTest's
 * singleton-container javadoc) — two pollers racing for the same {@code processing_jobs} rows
 * made an unrelated test ({@code ProductRecoveryIT}) flaky. Testing the service directly, with
 * mocks, proves the same idempotent-grant logic without paying for (or risking) a second context.
 */
class AdminBootstrapServiceTest {

    private final UserRepository userRepository = mock(UserRepository.class);

    @Test
    void bootstrapConfiguredAdmins_grantsAdmin_toMatchingExistingUser_idempotently() {
        User user = buyerNamed("configured-admin@example.com");
        when(userRepository.findWithRolesByEmail("configured-admin@example.com")).thenReturn(Optional.of(user));

        AdminBootstrapService service = new AdminBootstrapService(
                new AdminProperties(List.of("Configured-Admin@Example.com ")), userRepository);

        service.bootstrapConfiguredAdmins();
        service.bootstrapConfiguredAdmins(); // the "second start" — must not duplicate

        long adminRoleCount = user.getRoles().stream().filter(r -> r.getRole() == Role.ADMIN).count();
        assertThat(adminRoleCount).isEqualTo(1);
        assertThat(user.getRoles().stream().map(UserRole::getRole)).contains(Role.BUYER, Role.ADMIN);
        // save() is only called for the actual grant — the second, no-op bootstrap pass never
        // touches the repository again.
        verify(userRepository, times(1)).save(any(User.class));
    }

    @Test
    void bootstrapConfiguredAdmins_ignoresEmailsWithNoMatchingUser() {
        when(userRepository.findWithRolesByEmail(any())).thenReturn(Optional.empty());

        AdminBootstrapService service = new AdminBootstrapService(
                new AdminProperties(List.of("nobody@example.com")), userRepository);

        service.bootstrapConfiguredAdmins();

        verify(userRepository, times(0)).save(any());
    }

    @Test
    void grantAdminRoleIfConfigured_onConfiguredEmail_grantsAdmin() {
        User user = buyerNamed("second-admin@example.com");
        AdminBootstrapService service = new AdminBootstrapService(
                new AdminProperties(List.of("second-admin@example.com")), userRepository);

        service.grantAdminRoleIfConfigured(user);

        assertThat(user.getRoles().stream().map(UserRole::getRole)).contains(Role.ADMIN);
    }

    @Test
    void grantAdminRoleIfConfigured_onUnconfiguredEmail_doesNothing() {
        User user = buyerNamed("regular-user@example.com");
        AdminBootstrapService service = new AdminBootstrapService(
                new AdminProperties(List.of("someone-else@example.com")), userRepository);

        service.grantAdminRoleIfConfigured(user);

        assertThat(user.getRoles().stream().map(UserRole::getRole)).containsExactly(Role.BUYER);
        verify(userRepository, times(0)).save(any());
    }

    @Test
    void isConfiguredAdminEmail_isCaseInsensitiveAndTrimsWhitespace() {
        AdminBootstrapService service = new AdminBootstrapService(
                new AdminProperties(List.of(" Admin@Example.com ")), userRepository);

        assertThat(service.isConfiguredAdminEmail("admin@example.com")).isTrue();
        assertThat(service.isConfiguredAdminEmail("ADMIN@EXAMPLE.COM")).isTrue();
        assertThat(service.isConfiguredAdminEmail("someone-else@example.com")).isFalse();
    }

    private static User buyerNamed(String email) {
        User user = new User();
        user.setEmail(email);
        user.setDisplayName("Test User");
        user.setPasswordHash("hash");
        UserRole buyerRole = new UserRole();
        buyerRole.setUser(user);
        buyerRole.setRole(Role.BUYER);
        user.getRoles().add(buyerRole);
        return user;
    }
}
