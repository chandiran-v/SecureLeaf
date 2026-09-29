package com.secureleaf.creator.service;

import com.secureleaf.admin.entity.AdminActionType;
import com.secureleaf.admin.entity.AdminTargetType;
import com.secureleaf.admin.service.AdminAuditService;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.creator.dto.PayoutDto;
import com.secureleaf.creator.dto.PayoutPageDto;
import com.secureleaf.creator.entity.CreatorPayout;
import com.secureleaf.creator.entity.PayoutStatus;
import com.secureleaf.creator.repository.CreatorPayoutRepository;
import com.secureleaf.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Admin side of payouts (Phase 09C D3) — a state machine for money. Every action locks the payout
 * row, checks {@link PayoutStatus#canTransitionTo}, changes the state, writes exactly one
 * {@code admin_actions} row and one creator notification, all in one transaction.
 *
 * The transfer itself happens OUTSIDE the app (UPI/bank); "paid" is the admin recording that it did.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminPayoutService {

    private static final int MAX_PAGE_SIZE = 100;

    private final CreatorPayoutRepository payoutRepository;
    private final UserRepository userRepository;
    private final AdminAuditService adminAuditService;
    private final NotificationService notificationService;

    @Transactional(readOnly = true)
    public PayoutPageDto adminPayouts(PayoutStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<CreatorPayout> result = status == null
                ? payoutRepository.findAll(pageable)
                : payoutRepository.findByStatus(status, pageable);
        return new PayoutPageDto(result.getContent().stream().map(PayoutMapper::toDto).toList(),
                Math.toIntExact(result.getTotalElements()), result.getTotalPages(), result.getNumber());
    }

    @Transactional
    public PayoutDto approve(Long adminId, Long payoutId) {
        CreatorPayout p = transition(payoutId, PayoutStatus.APPROVED);
        p.setApprovedBy(userRepository.getReferenceById(adminId));
        adminAuditService.record(adminId, AdminActionType.APPROVE_PAYOUT, AdminTargetType.PAYOUT, payoutId, null);
        notificationService.notifyPayoutStatus(p, "Payout approved",
                "Your payout of " + rupees(p) + " was approved. We'll send it to " + p.getPayoutDestination()
                        + " and mark it paid once it has gone out.");
        return PayoutMapper.toDto(p);
    }

    @Transactional
    public PayoutDto markPaid(Long adminId, Long payoutId, String reference) {
        if (reference == null || reference.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "A transfer reference (e.g. the UPI transaction id) is required.");
        }
        String ref = reference.trim();
        CreatorPayout p = transition(payoutId, PayoutStatus.PAID);
        p.setPayoutReference(ref);
        p.setProcessedAt(Instant.now());
        adminAuditService.record(adminId, AdminActionType.MARK_PAYOUT_PAID, AdminTargetType.PAYOUT, payoutId, ref);
        notificationService.notifyPayoutStatus(p, "Payout sent",
                "Your payout of " + rupees(p) + " was sent to " + p.getPayoutDestination()
                        + ". Transfer reference: " + ref + ".");
        return PayoutMapper.toDto(p);
    }

    @Transactional
    public PayoutDto reject(Long adminId, Long payoutId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "A reason is required to reject a payout.");
        }
        String why = reason.trim();
        CreatorPayout p = transition(payoutId, PayoutStatus.REJECTED);
        p.setNotes(why);
        p.setProcessedAt(Instant.now());
        adminAuditService.record(adminId, AdminActionType.REJECT_PAYOUT, AdminTargetType.PAYOUT, payoutId, why);
        notificationService.notifyPayoutStatus(p, "Payout rejected",
                "Your payout request of " + rupees(p) + " was rejected: " + why
                        + ". The amount is back in your available balance.");
        return PayoutMapper.toDto(p);
    }

    private CreatorPayout transition(Long payoutId, PayoutStatus next) {
        CreatorPayout p = payoutRepository.findByIdForUpdate(payoutId)
                .orElseThrow(() -> new ResourceNotFoundException("Payout", payoutId));
        if (!p.getStatus().canTransitionTo(next)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Payout " + payoutId + " is " + p.getStatus() + " and cannot move to " + next + ".");
        }
        p.setStatus(next);
        log.info("Payout {} -> {}", payoutId, next);
        return p;
    }

    private static String rupees(CreatorPayout p) {
        return com.secureleaf.common.util.Money.formatRupees(p.getAmountPaise());
    }
}
