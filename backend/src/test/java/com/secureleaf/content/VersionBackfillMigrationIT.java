package com.secureleaf.content;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15, acceptance criterion 1 — V12's backfill, tested the only honest way: build a database
 * at schema V11 with MVP1-shaped data, THEN apply V12 and look at what it did. (A test that
 * starts from an empty, fully-migrated schema proves nothing about a backfill: there is nothing
 * to back-fill.) Uses its own throwaway container because it needs control over the Flyway target.
 */
class VersionBackfillMigrationIT {

    private static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("backfill").withUsername("t").withPassword("t");

    private static JdbcTemplate jdbc;
    private static Flyway flyway;

    @BeforeAll
    static void migrateToV11AndSeedMvp1Data() {
        DB.start();
        DriverManagerDataSource ds = new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword());
        jdbc = new JdbcTemplate(ds);
        flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("11").load();
        flyway.migrate();

        jdbc.update("INSERT INTO users (email, password_hash, display_name) VALUES ('c@x.test', 'h', 'C')");
        // categories are seeded by V3
        // product 1: two processed versions and a newer unprocessed one  -> latest PROCESSED (v2)
        // product 2: a single processed version                          -> v1
        // product 3: its only upload never finished                      -> stays NULL
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO products (creator_id, category_id, title, slug, description) VALUES (1, (SELECT min(id) FROM categories), ?, ?, 'd')",
                    "P" + i, "p" + i);
        }
        version(1, 1, true);
        version(1, 2, true);
        version(1, 3, false);
        version(2, 1, true);
        version(3, 1, false);
    }

    @AfterAll
    static void stop() {
        DB.stop();
    }

    private static void version(int productId, int number, boolean processed) {
        jdbc.update("""
                INSERT INTO document_versions (product_id, version_number, original_filename, file_size_bytes,
                    raw_minio_bucket, raw_minio_object_key, processed_at)
                VALUES (?, ?, 'f.pdf', 10, 'raw', ?, ?)""",
                productId, number, "k" + productId + "-" + number, processed ? java.sql.Timestamp.from(java.time.Instant.now()) : null);
    }

    private static Integer currentVersionNumber(int productId) {
        return jdbc.query("""
                SELECT v.version_number FROM products p
                LEFT JOIN document_versions v ON v.id = p.current_document_version_id WHERE p.id = ?""",
                rs -> rs.next() ? (Integer) rs.getObject(1) : null, productId);
    }

    @Test
    void v12_backfillsTheLatestProcessedVersion_andSetsSafeDefaults() {
        // Same database, same data; now let Flyway apply everything after V11 (i.e. V12).
        Flyway.configure().configuration(flyway.getConfiguration()).target("latest").load().migrate();

        assertThat(currentVersionNumber(1)).as("latest PROCESSED version wins over a newer unprocessed one").isEqualTo(2);
        assertThat(currentVersionNumber(2)).isEqualTo(1);
        assertThat(currentVersionNumber(3)).as("nothing processed yet -> no current version").isNull();

        // Every pre-existing version is NEW_BUYERS_ONLY and needs no entitlement migration.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_versions WHERE update_policy <> 'NEW_BUYERS_ONLY'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_versions WHERE entitlements_migrated_at IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document_versions WHERE retired_at IS NOT NULL", Integer.class)).isZero();
    }
}
