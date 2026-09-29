package com.secureleaf.admin.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.creator.dto.PayoutDto;
import com.secureleaf.creator.dto.PayoutPageDto;
import com.secureleaf.creator.entity.PayoutStatus;
import com.secureleaf.creator.service.AdminPayoutService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

/** Phase 09C D3 — admin payout workflow. {@code @PreAuthorize} is the role gate; the service holds the rules. */
@Controller
@RequiredArgsConstructor
public class AdminPayoutResolver {

    private final AdminPayoutService service;

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PayoutPageDto adminPayouts(@Argument PayoutStatus status, @Argument int page, @Argument int size) {
        return service.adminPayouts(status, page, size);
    }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PayoutDto approvePayout(@Argument Long id) {
        return service.approve(adminId(), id);
    }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PayoutDto markPayoutPaid(@Argument Long id, @Argument String reference) {
        return service.markPaid(adminId(), id, reference);
    }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PayoutDto rejectPayout(@Argument Long id, @Argument String reason) {
        return service.reject(adminId(), id, reason);
    }

    private static Long adminId() {
        return ((SecureLeafUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUserId();
    }
}
