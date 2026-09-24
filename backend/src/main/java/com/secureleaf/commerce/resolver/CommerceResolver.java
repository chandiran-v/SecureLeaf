package com.secureleaf.commerce.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.commerce.dto.CreatorEarningsDto;
import com.secureleaf.commerce.dto.EntitlementDto;
import com.secureleaf.commerce.dto.InitiateOrderPayload;
import com.secureleaf.commerce.dto.OrderDto;
import com.secureleaf.commerce.dto.VerifyPaymentInput;
import com.secureleaf.commerce.service.EntitlementService;
import com.secureleaf.commerce.service.OrderService;
import com.secureleaf.commerce.service.PaymentCompletionService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import java.util.List;

/**
 * GraphQL entry points for buying. Any authenticated user can buy (every account is a BUYER,
 * requirements.md "unified login"); earnings are CREATOR-only. Object-level rules live in the
 * services — the resolver only establishes WHO is asking.
 */
@Controller
@RequiredArgsConstructor
public class CommerceResolver {

    private final OrderService orderService;
    private final PaymentCompletionService paymentCompletionService;
    private final EntitlementService entitlementService;

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public InitiateOrderPayload initiateOrder(@Argument Long productId, @Argument String idempotencyKey) {
        return orderService.initiateOrder(getCurrentUserId(), productId, idempotencyKey);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public OrderDto verifyPayment(@Argument VerifyPaymentInput input) {
        return paymentCompletionService.verifyCheckout(getCurrentUserId(), input);
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public OrderDto order(@Argument Long id) {
        return orderService.getOrderForBuyer(id, getCurrentUserId());
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public List<EntitlementDto> myLibrary() {
        return entitlementService.myLibrary(getCurrentUserId());
    }

    @QueryMapping
    @PreAuthorize("hasRole('CREATOR')")
    public CreatorEarningsDto creatorEarnings() {
        return orderService.creatorEarnings(getCurrentUserId());
    }

    private Long getCurrentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
