package com.secureleaf.admin.service;

import com.secureleaf.admin.dto.AdminActionPageDto;
import com.secureleaf.admin.entity.AdminAction;
import com.secureleaf.admin.entity.AdminActionType;
import com.secureleaf.admin.entity.AdminTargetType;
import com.secureleaf.admin.mapper.AdminActionMapper;
import com.secureleaf.admin.repository.AdminActionRepository;
import com.secureleaf.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * D7 — the only way an {@link AdminAction} row is written. {@code Propagation.MANDATORY} means
 * calling this outside an existing transaction is a bug that fails loudly at startup-of-call
 * rather than silently writing the audit row in its own, separately-committed transaction — the
 * same guarantee {@code PaymentAuditService} makes for {@code payment_events} (D5 there): the
 * mutation and its audit row either both commit or both roll back, never one without the other.
 */
@Service
@RequiredArgsConstructor
public class AdminAuditService {

    private static final int MAX_PAGE_SIZE = 100;

    private final AdminActionRepository adminActionRepository;
    private final UserRepository userRepository;

    /** D7 — {@code adminActions}, newest first. */
    @Transactional(readOnly = true)
    public AdminActionPageDto adminActions(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return AdminActionMapper.toPageDto(adminActionRepository.findAllByOrderByCreatedAtDesc(pageable));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Long adminId, AdminActionType action, AdminTargetType targetType, Long targetId, String reason) {
        AdminAction row = new AdminAction();
        // getReferenceById: a lazy proxy, not a SELECT — the caller already knows adminId is a
        // real, authenticated ADMIN (checked by @PreAuthorize before the service method ran).
        row.setAdmin(userRepository.getReferenceById(adminId));
        row.setAction(action);
        row.setTargetType(targetType);
        row.setTargetId(targetId);
        row.setReason(reason);
        adminActionRepository.save(row);
    }
}
