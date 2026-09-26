package com.secureleaf.admin;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.admin.service.AdminBootstrapService;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8 acceptance criterion 2 — "ADMIN_EMAILS bootstrap grants ADMIN, and a second start
 * doesn't duplicate it." A real process restart isn't practical inside this test suite (the
 * Spring context is cached across a whole run — see AbstractIntegrationTest's javadoc), so this
 * calls {@link AdminBootstrapService#bootstrapConfiguredAdmins()} directly, twice, which is
 * exactly what {@link AdminBootstrapRunner} does once per real startup.
 */
class AdminBootstrapIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void adminEmails(DynamicPropertyRegistry registry) {
        registry.add("admin.emails", () -> "configured-admin@example.com, Second-Admin@Example.com ");
    }

    @Autowired private AdminBootstrapService adminBootstrapService;
    @Autowired private AuthService authService;
    @Autowired private UserRepository userRepository;

    @Test
    void bootstrap_grantsAdmin_toExistingUser_idempotently() {
        User user = new User();
        user.setEmail("configured-admin@example.com");
        user.setDisplayName("Configured Admin");
        user.setPasswordHash("hash");
        UserRole buyerRole = new UserRole();
        buyerRole.setUser(user);
        buyerRole.setRole(Role.BUYER);
        user.getRoles().add(buyerRole);
        user = userRepository.save(user);

        adminBootstrapService.bootstrapConfiguredAdmins();
        adminBootstrapService.bootstrapConfiguredAdmins(); // the "second start" — must not duplicate

        User reloaded = userRepository.findWithRolesById(user.getId()).orElseThrow();
        long adminRoleCount = reloaded.getRoles().stream().filter(r -> r.getRole() == Role.ADMIN).count();
        assertThat(adminRoleCount).isEqualTo(1);
        assertThat(reloaded.getRoles().stream().map(UserRole::getRole)).contains(Role.BUYER, Role.ADMIN);
    }

    @Test
    void registration_withConfiguredEmail_grantsAdminImmediately_caseInsensitively() {
        // Configured as "Second-Admin@Example.com " (mixed case, trailing space) — registering
        // with the normalized form must still match (D1: same normalizeEmail rule as login).
        User registered = authService.register("second-admin@example.com", "Password123!", "Second Admin");
        assertThat(registered.getRoles().stream().map(UserRole::getRole)).contains(Role.ADMIN, Role.BUYER);
    }

    @Test
    void registration_withUnconfiguredEmail_getsNoAdminRole() {
        User registered = authService.register("regular-user@example.com", "Password123!", "Regular User");
        assertThat(registered.getRoles().stream().map(UserRole::getRole)).containsExactly(Role.BUYER);
    }
}
