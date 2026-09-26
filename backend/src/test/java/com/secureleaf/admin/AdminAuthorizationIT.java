package com.secureleaf.admin;

import com.secureleaf.AbstractIntegrationTest;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8 acceptance criteria 1, 5, 8 (phase-08-admin-panel.md).
 *
 * D2 — every admin query/mutation carries {@code @PreAuthorize("hasRole('ADMIN')")}. This
 * class proves that mechanically, for a BUYER and a CREATOR, across every single one of them
 * (criterion 1) — a placeholder id like {@code 1} is enough because Spring Security's method
 * interceptor rejects the call before the resolver body (and the id it would look up) ever runs.
 */
class AdminAuthorizationIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User admin;
    private User otherAdmin;
    private HttpGraphQlTester asAdmin;
    private HttpGraphQlTester asBuyer;
    private HttpGraphQlTester asCreator;

    @BeforeEach
    void setUp() {
        admin = makeUser("admin@secureleaf.test", "Ada Admin", Role.BUYER, Role.ADMIN);
        otherAdmin = makeUser("other-admin@secureleaf.test", "Owen Admin", Role.BUYER, Role.ADMIN);
        User buyer = makeUser("buyer@secureleaf.test", "Bo Buyer", Role.BUYER);
        User creator = makeUser("creator@secureleaf.test", "Casey Creator", Role.BUYER, Role.CREATOR);

        asAdmin = tester(admin);
        asBuyer = tester(buyer);
        asCreator = tester(creator);
    }

    // ═══ 1. BUYER and CREATOR get ACCESS_DENIED for every admin query/mutation ═══

    private static Stream<Arguments> adminOnlyOperations() {
        return Stream.of(
                Arguments.of("adminUsers", "query { adminUsers(page: 0, size: 10) { totalElements } }"),
                Arguments.of("adminProducts", "query { adminProducts(page: 0, size: 10) { totalElements } }"),
                Arguments.of("platformStats", "query { platformStats(days: 30) { totalUsers } }"),
                Arguments.of("adminActions", "query { adminActions(page: 0, size: 10) { totalElements } }"),
                Arguments.of("suspendUser", "mutation { suspendUser(userId: 1, reason: \"x\") { id } }"),
                Arguments.of("reactivateUser", "mutation { reactivateUser(userId: 1) { id } }"),
                Arguments.of("takeDownProduct", "mutation { takeDownProduct(productId: 1, reason: \"x\") { id } }"),
                Arguments.of("restoreProduct", "mutation { restoreProduct(productId: 1) { id } }")
        );
    }

    @ParameterizedTest(name = "BUYER → {0} is ACCESS_DENIED")
    @MethodSource("adminOnlyOperations")
    void buyerGetsAccessDenied(String name, String document) {
        assertAccessDenied(asBuyer, document);
    }

    @ParameterizedTest(name = "CREATOR → {0} is ACCESS_DENIED")
    @MethodSource("adminOnlyOperations")
    void creatorGetsAccessDenied(String name, String document) {
        assertAccessDenied(asCreator, document);
    }

    private void assertAccessDenied(HttpGraphQlTester tester, String document) {
        tester.document(document).execute()
                .errors().expect(e -> "ACCESS_DENIED".equals(e.getExtensions().get("code"))).verify();
    }

    // ═══ 5. Suspending self or another admin is rejected ═══════════════════════

    private static final String SUSPEND = """
            mutation($id: ID!, $reason: String!) {
              suspendUser(userId: $id, reason: $reason) { id }
            }
            """;

    @Test
    void adminCannotSuspendSelf() {
        asAdmin.document(SUSPEND).variable("id", admin.getId()).variable("reason", "test")
                .execute().errors().expect(e -> "INVALID_INPUT".equals(e.getExtensions().get("code"))).verify();
    }

    @Test
    void adminCannotSuspendAnotherAdmin() {
        asAdmin.document(SUSPEND).variable("id", otherAdmin.getId()).variable("reason", "test")
                .execute().errors().expect(e -> "INVALID_INPUT".equals(e.getExtensions().get("code"))).verify();
    }

    // ═══ 8. Exactly one admin_actions row per mutation; append-only ════════════

    @Test
    void suspendUser_writesExactlyOneAuditRow() {
        User target = makeUser("target@secureleaf.test", "Tara Target", Role.BUYER);

        asAdmin.document(SUSPEND).variable("id", target.getId()).variable("reason", "policy violation")
                .execute().path("suspendUser.id").hasValue();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM admin_actions WHERE target_type = 'USER' AND target_id = ?", Integer.class, target.getId()))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT action FROM admin_actions WHERE target_id = ?", String.class, target.getId()))
                .isEqualTo("SUSPEND_USER");
    }

    @Test
    void adminActionsTable_isAppendOnly() {
        User target = makeUser("target2@secureleaf.test", "Tara Target Two", Role.BUYER);
        asAdmin.document(SUSPEND).variable("id", target.getId()).variable("reason", "policy violation")
                .execute().path("suspendUser.id").hasValue();

        Long rowId = jdbcTemplate.queryForObject(
                "SELECT id FROM admin_actions WHERE target_id = ?", Long.class, target.getId());

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE admin_actions SET reason = 'edited' WHERE id = ?", rowId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM admin_actions WHERE id = ?", rowId))
                .isInstanceOf(DataAccessException.class);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private User makeUser(String email, String name, Role... roles) {
        User user = new User();
        user.setEmail(email);
        user.setDisplayName(name);
        user.setPasswordHash("hash");
        for (Role role : roles) {
            UserRole userRole = new UserRole();
            userRole.setUser(user);
            userRole.setRole(role);
            user.getRoles().add(userRole);
        }
        return userRepository.save(user);
    }

    private HttpGraphQlTester tester(User user) {
        WebTestClient.Builder client = WebTestClient.bindToServer().baseUrl("http://localhost:" + port + "/graphql");
        client.defaultHeader("Authorization", "Bearer " + jwtService.generateAccessToken(user));
        return HttpGraphQlTester.create(client.build());
    }
}
