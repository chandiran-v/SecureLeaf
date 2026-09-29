package com.secureleaf.common;

import com.secureleaf.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9, D4 — GraphQL depth/complexity limiting. Acceptance criterion 3.
 *
 * Both queries below are deliberately built from the schema's own introspection meta-fields
 * ({@code __type}/{@code ofType}) and from aliasing — neither needs any authentication or any
 * domain data, so these tests are entirely self-contained.
 */
class GraphQlHardeningIT extends AbstractIntegrationTest {

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
    void queryDeeperThanTenLevels_isRejected() {
        // __Type.ofType is self-referential — chaining it N times builds a query exactly N+1
        // levels deep. 12 levels of ofType (13 total) comfortably exceeds the max depth of 10.
        String nested = "name";
        for (int i = 0; i < 12; i++) {
            nested = "name ofType { " + nested + " }";
        }
        String query = "{ __type(name: \"Product\") { " + nested + " } }";

        graphQlTester.document(query).execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    assertThat(errors.get(0).getMessage().toLowerCase()).contains("depth");
                });
    }

    @Test
    void queryOverTwoHundredComplexity_isRejected() {
        // Each alias re-selects `categories { id name }` (3 fields: the alias itself, id, name),
        // so 100 aliases already clears the complexity budget of 200 — aliasing is exactly the
        // trick complexity limiting exists to stop (depth limiting alone wouldn't catch this,
        // since every alias is only 2 levels deep).
        StringBuilder query = new StringBuilder("{ ");
        for (int i = 0; i < 100; i++) {
            query.append("a").append(i).append(": categories { id name } ");
        }
        query.append("}");

        graphQlTester.document(query.toString()).execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).isNotEmpty();
                    assertThat(errors.get(0).getMessage().toLowerCase()).contains("complexity");
                });
    }
}
