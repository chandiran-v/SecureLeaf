package com.secureleaf.commerce.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.commerce.service.EntitlementService;
import com.secureleaf.marketplace.dto.ProductDto;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * {@code Product.ownedByMe} (D11) — lets the detail page show "In your library" instead of Buy.
 *
 * WHY @BatchMapping AND NOT @SchemaMapping
 * A {@code @SchemaMapping} field resolver runs once PER PRODUCT. On a 20-product marketplace
 * page that's 20 entitlement queries — the N+1 problem from Phase 3 in a new costume.
 * {@code @BatchMapping} registers a DataLoader: GraphQL collects every Product in the response
 * that selected {@code ownedByMe}, then calls this method ONCE with the whole list → one
 * {@code WHERE product_id IN (…)} query.
 *
 * Lives in the commerce module (not ProductFieldResolver) because ownership is commerce
 * knowledge; the marketplace module stays unaware that entitlements exist.
 */
@Controller
@RequiredArgsConstructor
public class ProductOwnershipResolver {

    private final EntitlementService entitlementService;

    @BatchMapping(typeName = "Product", field = "ownedByMe")
    public List<Boolean> ownedByMe(List<ProductDto> products, Principal principal) {
        Long buyerId = currentUserId(principal);
        if (buyerId == null) {
            // Anonymous visitor — owns nothing. Also no query at all.
            return Collections.nCopies(products.size(), false);
        }
        Set<Long> owned = entitlementService.ownedProductIds(buyerId,
                products.stream().map(ProductDto::id).toList());
        // A batch loader must return results in the SAME ORDER as its keys.
        return products.stream().map(p -> owned.contains(p.id())).toList();
    }

    private static Long currentUserId(Principal principal) {
        if (principal instanceof Authentication auth
                && auth.getPrincipal() instanceof SecureLeafUserDetails details) {
            return details.getUserId();
        }
        return null;   // null or AnonymousAuthenticationToken ("anonymousUser")
    }
}
