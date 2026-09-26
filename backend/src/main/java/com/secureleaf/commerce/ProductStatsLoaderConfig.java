package com.secureleaf.commerce;

import com.secureleaf.commerce.dto.ProductStatsDto;
import com.secureleaf.commerce.repository.OrderItemRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.stream.Collectors;

/**
 * Registers a single, NAMED DataLoader ("productStats") that {@code Product.salesCount} and
 * {@code Product.netEarningsPaise} both draw from (Phase 6, D2).
 *
 * WHY A NAMED LOADER INSTEAD OF TWO @BatchMapping METHODS
 * {@code @BatchMapping(typeName="Product", field="salesCount")} would work on its own — same
 * pattern as {@link com.secureleaf.commerce.resolver.ProductOwnershipResolver} — but Spring for
 * GraphQL gives each annotated field its OWN DataLoader, keyed by "Product.salesCount" /
 * "Product.netEarningsPaise". A dashboard row selects both fields, so that would run the
 * aggregate query TWICE per request — once per field — even though it's the exact same
 * {@code GROUP BY product_id} query both times.
 *
 * Registering ONE loader under an explicit name, and having both fields' resolvers
 * ({@link com.secureleaf.commerce.resolver.ProductStatsResolver}) call
 * {@code DataFetchingEnvironment.getDataLoaderRegistry().getDataLoader("productStats")}, means
 * graphql-java's DataLoader machinery collects every {@code .load(productId)} call made while
 * resolving the response — from BOTH fields — into one batch, dispatched as one query. This is
 * the same "batch + dedupe" idea @BatchMapping already uses; the only difference is the loader is
 * shared across two schema fields instead of dedicated to one.
 */
@Component
@RequiredArgsConstructor
public class ProductStatsLoaderConfig {

    public static final String LOADER_NAME = "productStats";

    private final BatchLoaderRegistry batchLoaderRegistry;
    private final OrderItemRepository orderItemRepository;

    @PostConstruct
    void registerProductStatsLoader() {
        batchLoaderRegistry.<Long, ProductStatsDto>forName(LOADER_NAME)
                .registerMappedBatchLoader((productIds, env) -> Mono.fromCallable(() ->
                        orderItemRepository.sumStatsByProductIds(productIds).stream()
                                .collect(Collectors.toMap(ProductStatsDto::productId, dto -> dto))));
    }
}
