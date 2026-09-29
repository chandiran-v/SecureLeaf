package com.secureleaf.common;

import com.secureleaf.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9, D4 — proves the mechanism application-prod.yml relies on (
 * {@code spring.graphql.schema.introspection.enabled: false}) actually blocks introspection
 * queries. Acceptance criterion 3's second half.
 *
 * WHY THIS DOESN'T JUST ACTIVATE THE 'prod' SPRING PROFILE
 * Activating 'prod' for real would also arm every prod-only fail-fast check (ProdSecretsConfig,
 * DrmConfig, CommerceConfig's gateway guard — Phase 9 D6 / earlier phases), which would then
 * refuse to start unless every secret and the payment provider were overridden to production-
 * shaped values too — none of which this test cares about. Overriding just the one property under
 * test, while staying on the 'test' profile, proves the exact mechanism prod relies on without
 * dragging in unrelated fail-fast machinery. (This does spin up a second, separately-cached Spring
 * context — same "a new property value means a new application" cost the Phase 8 learning note's
 * Gotchas table describes — but it doesn't share the main suite's Postgres in a way that races.)
 */
@TestPropertySource(properties = "spring.graphql.schema.introspection.enabled=false")
class IntrospectionDisabledIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    private HttpGraphQlTester graphQlTester;

    @BeforeEach
    void setUp() {
        WebTestClient client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + port + "/graphql")
                .build();
        graphQlTester = HttpGraphQlTester.create(client);
    }

    @Test
    void introspectionQuery_isRejected_whenDisabled() {
        graphQlTester.document("{ __schema { types { name } } }").execute()
                .errors().satisfy(errors -> assertThat(errors).isNotEmpty());
    }
}
