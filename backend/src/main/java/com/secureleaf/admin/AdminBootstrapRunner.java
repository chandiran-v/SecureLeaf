package com.secureleaf.admin;

import com.secureleaf.admin.service.AdminBootstrapService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * D1 — runs once at startup, after the Flyway migration and the Spring context are both ready.
 * Idempotent: re-running it (every restart) grants nothing new to a user who already has ADMIN.
 */
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements ApplicationRunner {

    private final AdminBootstrapService adminBootstrapService;

    @Override
    public void run(ApplicationArguments args) {
        adminBootstrapService.bootstrapConfiguredAdmins();
    }
}
