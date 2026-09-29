package com.secureleaf.common.config;

import graphql.analysis.MaxQueryComplexityInstrumentation;
import graphql.analysis.MaxQueryDepthInstrumentation;
import graphql.execution.instrumentation.Instrumentation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 9, D4 — GraphQL-specific denial-of-service hardening.
 *
 * WHY A GRAPHQL API NEEDS THIS AND A REST API DOESN'T (AS MUCH)
 * A REST endpoint's cost is roughly fixed per route — {@code GET /api/products/42} always does
 * about the same amount of work. GraphQL lets the *caller* compose arbitrarily large queries out
 * of the schema's own building blocks: nesting {@code creator { products { creator { products {
 * ... } } } } }} as deep as the schema allows, or aliasing the same expensive field hundreds of
 * times in one request ({@code a: title b: title c: title ...}), turns one HTTP request into
 * thousands of resolver calls server-side. Depth and complexity limits cap how much a single
 * query is allowed to ask for, independent of any per-field rate limiting.
 *
 * - Max depth 10: rejects a query nested more than 10 levels deep, mostly a guard against the
 *   introspection schema's own self-referential {@code ofType} chains and any future recursive
 *   type in the domain schema.
 * - Max complexity 200: rejects a query whose total selected-field count (aliases included)
 *   exceeds 200 — the alias trick above doesn't get around this, since graphql-java's default
 *   complexity calculator counts each alias as its own field.
 * - Introspection disabled in {@code prod}: introspection (the {@code __schema}/{@code __type}
 *   meta-queries that let a client discover the entire schema — every field, every argument,
 *   every type) is invaluable in development (it's what powers GraphiQL's autocomplete) and a
 *   free reconnaissance tool for an attacker in production, handing them the exact shape of every
 *   mutation and query without needing the source code at all. Spring Boot 3.3 has a built-in
 *   property for this ({@code spring.graphql.schema.introspection.enabled: false}, set in
 *   application-prod.yml) — no custom bean needed, unlike the depth/complexity limits below,
 *   which graphql-java ships as {@link Instrumentation}s with no Boot property of their own.
 */
@Configuration
public class GraphQlHardeningConfig {

    private static final int MAX_QUERY_DEPTH = 10;
    private static final int MAX_QUERY_COMPLEXITY = 200;

    @Bean
    public Instrumentation maxQueryDepthInstrumentation() {
        return new MaxQueryDepthInstrumentation(MAX_QUERY_DEPTH);
    }

    @Bean
    public Instrumentation maxQueryComplexityInstrumentation() {
        return new MaxQueryComplexityInstrumentation(MAX_QUERY_COMPLEXITY);
    }
}
