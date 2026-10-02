package com.secureleaf.loadtest;

import com.secureleaf.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 acceptance criterion 4 (D4) — a first run creates 3 buyers with entitlements, a second call creates nothing, and users.csv lists every buyer.
 * The "throws under prod" half is in {@link LoadTestSeederProdGuardTest}.
 */
@ActiveProfiles({"test", "loadtest"})
@TestPropertySource(properties = {
        "loadtest.users=3",
        "loadtest.output=target/loadtest-it/users.csv",
        "processing.worker.enabled=false"   // secondary context: the seeder drives its own job
})
class LoadTestSeederIT extends AbstractIntegrationTest {

    @Autowired private LoadTestSeeder seeder;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void seedsBuyersWithEntitlements_andASecondRunCreatesNothing() throws Exception {
        // The ApplicationRunner also ran at startup, but AbstractIntegrationTest truncates every
        // table before each test, so the first explicit call below starts from an empty database.
        assertThat(seeder.seed()).isEqualTo(3);
        assertThat(count("users WHERE email LIKE 'loadtest-buyer-%'")).isEqualTo(3);
        assertThat(count("entitlements e JOIN users u ON u.id = e.buyer_id "
                + "WHERE u.email LIKE 'loadtest-buyer-%' AND e.status = 'ACTIVE'")).isEqualTo(3);
        // the bundled PDF has 12 pages, each rendered once per configured variant (Phase 16)
        assertThat(count("content_pages WHERE variant = 'DESKTOP'")).isEqualTo(12);
        assertThat(count("content_pages WHERE variant = 'MOBILE'")).isEqualTo(12);

        long entitlementsBefore = count("entitlements");
        long ordersBefore = count("orders");

        assertThat(seeder.seed()).isZero();

        assertThat(count("entitlements")).isEqualTo(entitlementsBefore);
        assertThat(count("orders")).isEqualTo(ordersBefore);
        assertThat(count("users WHERE email LIKE 'loadtest-buyer-%'")).isEqualTo(3);
        assertThat(count("products WHERE slug = 'loadtest-multi-page-book'")).isEqualTo(1);

        List<String> lines = Files.readAllLines(Path.of("target/loadtest-it/users.csv"));
        assertThat(lines).hasSize(4);
        assertThat(lines.get(0)).isEqualTo("email,password,productId");
        assertThat(lines.get(1)).startsWith("loadtest-buyer-0001@secureleaf.test,");
    }

    private long count(String fromWhere) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM " + fromWhere, Long.class);
        return n == null ? 0 : n;
    }
}
