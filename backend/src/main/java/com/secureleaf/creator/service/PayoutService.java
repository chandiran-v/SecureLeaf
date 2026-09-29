package com.secureleaf.creator.service;

import com.secureleaf.auth.entity.AccountStatus;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.commerce.dto.CreatorEarningsDto;
import com.secureleaf.commerce.entity.OrderStatus;
import com.secureleaf.commerce.repository.OrderItemRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.creator.PayoutProperties;
import com.secureleaf.creator.dto.CreatorBalanceDto;
import com.secureleaf.creator.dto.PayoutDetailsDto;
import com.secureleaf.creator.dto.PayoutDto;
import com.secureleaf.creator.entity.CreatorPayout;
import com.secureleaf.creator.entity.CreatorProfile;
import com.secureleaf.creator.entity.PayoutStatus;
import com.secureleaf.creator.repository.CreatorPayoutRepository;
import com.secureleaf.creator.repository.CreatorProfileRepository;
import com.secureleaf.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Creator side of payouts (Phase 09C D1, D2).
 *
 * LEDGER THINKING (D1): nothing here stores a balance. The balance is computed on every read from
 * events that already exist — earnings on completed order items, minus payout rows. A stored
 * "balance" column would have to be updated by every sale, refund and payout, and any bug or
 * missed path silently makes it wrong; a derived figure cannot drift from the facts it is derived from.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutService {

    private static final int MAX_DESTINATION_LENGTH = 100;

    private final OrderItemRepository orderItemRepository;
    private final CreatorPayoutRepository payoutRepository;
    private final CreatorProfileRepository profileRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final PayoutProperties properties;

    /** D1 — aggregate SQL only; refunded orders drop out because only COMPLETED items are summed. */
    @Transactional(readOnly = true)
    public CreatorBalanceDto balance(Long creatorId) {
        return computeBalance(creatorId, orderItemRepository.sumEarningsForCreator(creatorId));
    }

    private CreatorBalanceDto computeBalance(Long creatorId, CreatorEarningsDto lifetime) {
        Instant cutoff = Instant.now().minus(Duration.ofDays(properties.holdDays()));
        long cleared = orderItemRepository.sumEarningsCompletedUpTo(creatorId, OrderStatus.COMPLETED, cutoff);
        long committed = payoutRepository.sumAmountExcludingStatus(creatorId, PayoutStatus.REJECTED);
        long paidOut = payoutRepository.sumAmountWithStatus(creatorId, PayoutStatus.PAID);
        long lifetimeNet = lifetime.netEarningsPaise();
        // A refund AFTER a payout can push cleared below committed; the creator simply has nothing
        // available (we never show a negative balance). Recovering that money is a manual matter.
        long available = Math.max(0, cleared - committed);
        return new CreatorBalanceDto(available, lifetimeNet - cleared, lifetimeNet, paidOut);
    }

    /**
     * D2 — ask for money. Everything below the lock is check-then-act, so the lock comes first:
     * the second of two simultaneous requests waits here, then sees the first one's open request.
     */
    @Transactional
    public PayoutDto requestPayout(Long creatorId, long amountPaise) {
        profileRepository.insertIfMissing(creatorId);
        CreatorProfile profile = profileRepository.findByIdForUpdate(creatorId)
                .orElseThrow(() -> new ResourceNotFoundException("CreatorProfile", creatorId));

        String method;
        String destination;
        if (notBlank(profile.getPayoutUpi())) {
            method = "UPI";
            destination = profile.getPayoutUpi().trim();
        } else if (notBlank(profile.getPayoutEmail())) {
            method = "EMAIL";
            destination = profile.getPayoutEmail().trim();
        } else {
            throw new BusinessException(ErrorCode.PAYOUT_DETAILS_MISSING,
                    "Add a UPI id or payout email before requesting a payout.");
        }
        if (amountPaise < properties.minAmountPaise()) {
            throw new BusinessException(ErrorCode.PAYOUT_BELOW_MINIMUM,
                    "The minimum payout is " + com.secureleaf.common.util.Money.formatRupees(properties.minAmountPaise()) + ".");
        }
        if (payoutRepository.countByCreatorIdAndStatusIn(creatorId,
                List.of(PayoutStatus.REQUESTED, PayoutStatus.APPROVED, PayoutStatus.PROCESSING)) > 0) {
            throw new BusinessException(ErrorCode.PAYOUT_ALREADY_OPEN,
                    "You already have a payout request in progress. Wait for it to be paid or rejected.");
        }
        CreatorEarningsDto lifetime = orderItemRepository.sumEarningsForCreator(creatorId);
        CreatorBalanceDto balance = computeBalance(creatorId, lifetime);
        if (amountPaise > balance.availablePaise()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE,
                    "You asked for more than your available balance ("
                            + com.secureleaf.common.util.Money.formatRupees(balance.availablePaise()) + ").");
        }

        CreatorPayout payout = new CreatorPayout();
        payout.setCreator(userRepository.getReferenceById(creatorId));
        payout.setAmountPaise(amountPaise);
        // Snapshot of the lifetime split at request time, so the row explains itself later even if
        // the commission rate changes. The amount actually sent is net_payout_paise.
        payout.setGrossRevenuePaise(lifetime.grossSalesPaise());
        payout.setPlatformFeePaise(lifetime.platformFeePaise());
        payout.setNetPayoutPaise(amountPaise);
        payout.setPayoutMethod(method);
        payout.setPayoutDestination(destination);
        payout = payoutRepository.save(payout);

        User creator = payout.getCreator();
        List<User> admins = userRepository.findByRoleAndStatus(Role.ADMIN, AccountStatus.ACTIVE);
        notificationService.notifyPayoutRequested(admins, payout, creator);
        log.info("Payout {} requested: creator={}, amount={} paise", payout.getId(), creatorId, amountPaise);
        return PayoutMapper.toDto(payout);
    }

    @Transactional(readOnly = true)
    public List<PayoutDto> myPayouts(Long creatorId) {
        return payoutRepository.findByCreatorIdOrderByIdDesc(creatorId).stream().map(PayoutMapper::toDto).toList();
    }

    @Transactional(readOnly = true)
    public PayoutDetailsDto payoutDetails(Long creatorId) {
        return profileRepository.findById(creatorId)
                .map(p -> new PayoutDetailsDto(p.getPayoutUpi(), p.getPayoutEmail()))
                .orElse(new PayoutDetailsDto(null, null));
    }

    /** D2 — the "form to edit them". Blank clears a field. Applies to FUTURE requests only (snapshot). */
    @Transactional
    public PayoutDetailsDto updatePayoutDetails(Long creatorId, String payoutUpi, String payoutEmail) {
        String upi = normalise(payoutUpi);
        String email = normalise(payoutEmail);
        if (upi != null && (upi.length() > MAX_DESTINATION_LENGTH || !upi.matches("[A-Za-z0-9._-]{2,64}@[A-Za-z0-9]{2,64}"))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "That doesn't look like a UPI id (e.g. name@bank).");
        }
        if (email != null && (email.length() > 255 || !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+"))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "That doesn't look like an email address.");
        }
        profileRepository.insertIfMissing(creatorId);
        CreatorProfile profile = profileRepository.findByIdForUpdate(creatorId)
                .orElseThrow(() -> new ResourceNotFoundException("CreatorProfile", creatorId));
        profile.setPayoutUpi(upi);
        profile.setPayoutEmail(email);
        return new PayoutDetailsDto(upi, email);
    }

    private static String normalise(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
