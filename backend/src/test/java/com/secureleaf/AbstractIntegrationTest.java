package com.secureleaf;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for all integration tests.
 *
 * Uses Testcontainers to spin up a real PostgreSQL 15 database for the test
 * lifecycle. This guarantees our tests run against the exact same database
 * dialect as production, catching Postgres-specific SQL errors (like the
 * FOR UPDATE SKIP LOCKED syntax) that H2 would miss.
 *
 * SINGLETON CONTAINER PATTERN (fixed in Phase 4)
 * This used to be {@code @Testcontainers} + {@code @Container static}. That combination
 * starts and STOPS the container once per test class — but Spring caches the application
 * context across classes, so the second class reused a Hikari pool still pointing at the
 * first, now-stopped container and every test died with "Failed to obtain JDBC Connection"
 * after a 60s timeout. Each class passed alone; the suite could never pass together.
 *
 * Starting the container once in a static initializer (and never stopping it — the
 * Testcontainers "Ryuk" reaper removes it when the JVM exits) gives one database for the
 * whole run, matching the one cached Spring context. Tests isolate themselves by cleaning
 * tables in @BeforeEach instead.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("secureleaf_test")
            .withUsername("testuser")
            .withPassword("testpass");

    static {
        postgres.start();
    }

    @Autowired
    private JdbcTemplate baseJdbcTemplate;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // MinIO properties are irrelevant since InMemoryStorageService is @Primary
    }

    /**
     * One shared database means one test class's leftovers are the next class's foreign-key
     * errors (e.g. processing_jobs rows blocking products.deleteAll()). Truncating EVERY
     * application table before each test — discovered from pg_tables, so new tables are
     * covered automatically — gives each test a clean slate regardless of run order.
     *
     * TRUNCATE (not DELETE) also bypasses row-level triggers such as the append-only guard on
     * payment_events, and RESTART IDENTITY resets BIGSERIAL counters. Runs before any
     * subclass @BeforeEach (JUnit runs superclass lifecycle methods first).
     */
    @BeforeEach
    void truncateAllTables() {
        baseJdbcTemplate.execute("""
                DO $$
                DECLARE tables text;
                BEGIN
                    SELECT string_agg(format('%I', tablename), ', ') INTO tables
                    FROM pg_tables
                    WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history';
                    EXECUTE 'TRUNCATE ' || tables || ' RESTART IDENTITY CASCADE';
                END $$;
                """);
    }
}
