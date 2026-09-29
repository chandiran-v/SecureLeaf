package com.secureleaf.creator.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.creator.dto.CreatorBalanceDto;
import com.secureleaf.creator.dto.CreatorStatementDto;
import com.secureleaf.creator.dto.PayoutDetailsDto;
import com.secureleaf.creator.dto.PayoutDto;
import com.secureleaf.creator.service.PayoutService;
import com.secureleaf.creator.service.StatementService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import java.util.List;

/** Phase 09C — creator balance, payout requests and statements. CREATOR-only; the id always comes from the JWT. */
@Controller
@RequiredArgsConstructor
public class PayoutResolver {

    private final PayoutService payoutService;
    private final StatementService statementService;

    @QueryMapping
    @PreAuthorize("hasRole('CREATOR')")
    public CreatorBalanceDto creatorBalance() {
        return payoutService.balance(currentUserId());
    }

    @QueryMapping
    @PreAuthorize("hasRole('CREATOR')")
    public List<PayoutDto> myPayouts() {
        return payoutService.myPayouts(currentUserId());
    }

    @QueryMapping
    @PreAuthorize("hasRole('CREATOR')")
    public PayoutDetailsDto myPayoutDetails() {
        return payoutService.payoutDetails(currentUserId());
    }

    @QueryMapping
    @PreAuthorize("hasRole('CREATOR')")
    public CreatorStatementDto creatorStatement(@Argument String month) {
        return statementService.statement(currentUserId(), month);
    }

    @MutationMapping
    @PreAuthorize("hasRole('CREATOR')")
    public PayoutDto requestPayout(@Argument Long amountPaise) {
        return payoutService.requestPayout(currentUserId(), amountPaise);
    }

    @MutationMapping
    @PreAuthorize("hasRole('CREATOR')")
    public PayoutDetailsDto updatePayoutDetails(@Argument String payoutUpi, @Argument String payoutEmail) {
        return payoutService.updatePayoutDetails(currentUserId(), payoutUpi, payoutEmail);
    }

    private static Long currentUserId() {
        return ((SecureLeafUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getUserId();
    }
}
