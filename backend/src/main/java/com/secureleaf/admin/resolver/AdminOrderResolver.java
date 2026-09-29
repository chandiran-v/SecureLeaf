package com.secureleaf.admin.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.commerce.dto.OrderDto;
import com.secureleaf.commerce.service.RefundService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

/**
 * Phase 09B D6 — admin-only refunds. Same authorisation split as {@link AdminProductResolver}:
 * {@code @PreAuthorize} is the role gate; the service holds the rules (and writes the
 * {@code admin_actions} audit row in the same transaction).
 */
@Controller
@RequiredArgsConstructor
public class AdminOrderResolver {

    private final RefundService refundService;

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public OrderDto refundOrder(@Argument Long orderId, @Argument String reason) {
        SecureLeafUserDetails admin = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return refundService.refundOrder(admin.getUserId(), orderId, reason);
    }
}
