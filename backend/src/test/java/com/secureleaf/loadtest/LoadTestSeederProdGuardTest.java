package com.secureleaf.loadtest;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** Phase 10 acceptance criterion 4 (D4) — the seeder refuses to run when 'prod' is active. */
class LoadTestSeederProdGuardTest {

    @Test
    void throwsUnderProd_beforeTouchingAnything() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("loadtest", "prod");
        // Every collaborator is a bare mock: if the guard didn't fire first, the first repository
        // call would NPE/return null and the assertion on the message would fail.
        LoadTestSeeder seeder = new LoadTestSeeder(env,
                mock(com.secureleaf.auth.repository.UserRepository.class),
                mock(com.secureleaf.marketplace.repository.CategoryRepository.class),
                mock(com.secureleaf.marketplace.repository.ProductRepository.class),
                mock(com.secureleaf.content.repository.DocumentVersionRepository.class),
                mock(com.secureleaf.creator.repository.ProcessingJobRepository.class),
                mock(com.secureleaf.content.service.DocumentProcessingService.class),
                mock(com.secureleaf.commerce.repository.OrderRepository.class),
                mock(com.secureleaf.commerce.repository.EntitlementRepository.class),
                mock(com.secureleaf.common.storage.StorageService.class),
                mock(com.secureleaf.common.config.MinioProperties.class),
                mock(org.springframework.security.crypto.password.PasswordEncoder.class),
                5, "pw", "target/never-written.csv", 1);

        assertThatThrownBy(seeder::seed)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
    }
}
