package com.secureleaf.commerce.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.commerce.ProductStatsLoaderConfig;
import com.secureleaf.commerce.dto.ProductStatsDto;
import com.secureleaf.marketplace.dto.ProductDto;
import graphql.schema.DataFetchingEnvironment;
import lombok.RequiredArgsConstructor;
import org.dataloader.DataLoader;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.concurrent.CompletableFuture;

/**
 * {@code Product.salesCount} / {@code Product.netEarningsPaise} (Phase 6, D2) — visible only to
 * the product's own creator; every other caller (another creator, a buyer, an anonymous visitor)
 * gets {@code null}, checked here per product rather than trusting the client to not ask.
 *
 * Both fields are {@code @SchemaMapping} (called once per product, not once for the whole list)
 * but neither runs a query per call: they load through the SAME named DataLoader
 * ({@link ProductStatsLoaderConfig#LOADER_NAME}), which graphql-java batches into one aggregate
 * SQL query for every product in the response that asked for either field — see
 * {@link ProductStatsLoaderConfig}'s javadoc for why a shared name (not two separate
 * {@code @BatchMapping} methods) is what makes it exactly one query instead of two.
 */
@Controller
@RequiredArgsConstructor
public class ProductStatsResolver {

    @SchemaMapping(typeName = "Product", field = "salesCount")
    public CompletableFuture<Integer> salesCount(ProductDto product, DataFetchingEnvironment env, Principal principal) {
        return loadOwnStats(product, env, principal).thenApply(stats -> stats == null ? null : (int) stats.salesCount());
    }

    @SchemaMapping(typeName = "Product", field = "netEarningsPaise")
    public CompletableFuture<Long> netEarningsPaise(ProductDto product, DataFetchingEnvironment env, Principal principal) {
        return loadOwnStats(product, env, principal).thenApply(stats -> stats == null ? null : stats.netEarningsPaise());
    }

    /** @return the product's stats, or a completed future of {@code null} if the caller isn't its creator. */
    private CompletableFuture<ProductStatsDto> loadOwnStats(ProductDto product, DataFetchingEnvironment env, Principal principal) {
        Long callerId = currentUserId(principal);
        if (callerId == null || !product.creator().id().equals(callerId)) {
            return CompletableFuture.completedFuture(null);
        }
        DataLoader<Long, ProductStatsDto> loader = env.getDataLoaderRegistry().getDataLoader(ProductStatsLoaderConfig.LOADER_NAME);
        // Products with no completed sales have no row in the aggregate query's result — the
        // DataLoader resolves those keys to null, so the owner correctly sees zero, not "hidden".
        return loader.load(product.id()).thenApply(stats -> stats == null ? ProductStatsDto.empty(product.id()) : stats);
    }

    private static Long currentUserId(Principal principal) {
        if (principal instanceof Authentication auth
                && auth.getPrincipal() instanceof SecureLeafUserDetails details) {
            return details.getUserId();
        }
        return null;   // null or AnonymousAuthenticationToken ("anonymousUser")
    }
}
