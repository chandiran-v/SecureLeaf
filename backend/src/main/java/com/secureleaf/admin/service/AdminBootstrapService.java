package com.secureleaf.admin.service;

import com.secureleaf.admin.AdminProperties;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * D1 — grants ADMIN to every user whose (normalized) email is in {@code admin.emails}. Used two
 * ways, both idempotent:
 *   1. {@link com.secureleaf.admin.AdminBootstrapRunner} calls {@link #bootstrapConfiguredAdmins()}
 *      once at startup, for accounts that already exist.
 *   2. {@code AuthService.register()} calls {@link #grantAdminRoleIfConfigured} for a brand-new
 *      account, so registering with a configured email doesn't require a restart to pick it up.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminBootstrapService {

    private final AdminProperties adminProperties;
    private final UserRepository userRepository;

    @Transactional
    public void bootstrapConfiguredAdmins() {
        for (String rawEmail : adminProperties.emails()) {
            String normalized = AuthService.normalizeEmail(rawEmail);
            if (normalized == null || normalized.isBlank()) {
                continue;
            }
            userRepository.findWithRolesByEmail(normalized).ifPresent(this::grantAdminRoleIfAbsent);
        }
    }

    /** Called from {@code AuthService.register()} — the new user is already a managed entity in
     *  the caller's transaction, so adding a role here and letting it commit together is the
     *  same idempotent-grant recipe {@code AuthService.assignDefaultRole} uses for BUYER. */
    @Transactional
    public void grantAdminRoleIfConfigured(User user) {
        if (isConfiguredAdminEmail(user.getEmail())) {
            grantAdminRoleIfAbsent(user);
        }
    }

    public boolean isConfiguredAdminEmail(String email) {
        String normalized = AuthService.normalizeEmail(email);
        if (normalized == null) {
            return false;
        }
        return adminProperties.emails().stream()
                .map(AuthService::normalizeEmail)
                .anyMatch(normalized::equals);
    }

    private void grantAdminRoleIfAbsent(User user) {
        boolean alreadyAdmin = user.getRoles().stream().anyMatch(r -> r.getRole() == Role.ADMIN);
        if (alreadyAdmin) {
            return;
        }
        UserRole adminRole = new UserRole();
        adminRole.setUser(user);
        adminRole.setRole(Role.ADMIN);
        user.getRoles().add(adminRole);
        userRepository.save(user);
        log.info("ADMIN role granted to user id={}, email={} (ADMIN_EMAILS bootstrap)", user.getId(), user.getEmail());
    }
}
