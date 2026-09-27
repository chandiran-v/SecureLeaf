package com.secureleaf.admin.service;

import com.secureleaf.admin.dto.AdminUserDto;
import com.secureleaf.admin.dto.AdminUserFilterDto;
import com.secureleaf.admin.dto.AdminUserPageDto;
import com.secureleaf.admin.entity.AdminActionType;
import com.secureleaf.admin.entity.AdminTargetType;
import com.secureleaf.admin.mapper.AdminUserMapper;
import com.secureleaf.admin.repository.AdminUserQueryRepository;
import com.secureleaf.admin.repository.IdPage;
import com.secureleaf.auth.entity.AccountStatus;
import com.secureleaf.auth.entity.Role;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.RefreshTokenRepository;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.security.SuspendedUsersService;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.marketplace.repository.ProductRepository;
import com.secureleaf.viewer.entity.ViewerSessionEndReason;
import com.secureleaf.viewer.service.ViewerSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * D3/D4 — {@code adminUsers} search and the {@code suspendUser}/{@code reactivateUser}
 * mutations. Lives in {@code com.secureleaf.admin}, its own package (D2): every admin-only
 * capability the app has is findable in one place, and every method here is only ever reached
 * through a {@code @PreAuthorize("hasRole('ADMIN')")}-guarded resolver.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminUserService {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final AdminUserQueryRepository adminUserQueryRepository;
    private final ProductRepository productRepository;
    private final EntitlementRepository entitlementRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ViewerSessionService viewerSessionService;
    private final SuspendedUsersService suspendedUsersService;
    private final AdminAuditService adminAuditService;

    // ── Read ─────────────────────────────────────────────────────────────────

    /**
     * Bounded query count regardless of page size (acceptance criterion 3): one id-page query,
     * one count query, one entity fetch, one productCount aggregate, one purchaseCount
     * aggregate — five, always, whether the page holds 1 row or 100.
     */
    @Transactional(readOnly = true)
    public AdminUserPageDto adminUsers(AdminUserFilterDto filter, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        IdPage idPage = adminUserQueryRepository.searchUserIds(filter, pageable);

        if (idPage.ids().isEmpty()) {
            return new AdminUserPageDto(List.of(), Math.toIntExact(idPage.totalElements()), 0, pageable.getPageNumber());
        }

        List<User> users = reorder(idPage.ids(), userRepository.findAllByIdIn(idPage.ids()));
        Map<Long, Long> productCounts = toCountMap(productRepository.countByCreatorIdsGrouped(idPage.ids()));
        Map<Long, Long> purchaseCounts = toCountMap(entitlementRepository.countByBuyerIdsGrouped(idPage.ids()));

        List<AdminUserDto> content = users.stream()
                .map(u -> AdminUserMapper.toDto(u,
                        productCounts.getOrDefault(u.getId(), 0L).intValue(),
                        purchaseCounts.getOrDefault(u.getId(), 0L).intValue()))
                .toList();

        int totalPages = idPage.totalElements() == 0
                ? 0
                : (int) Math.ceil(idPage.totalElements() / (double) pageable.getPageSize());
        return new AdminUserPageDto(content, Math.toIntExact(idPage.totalElements()), totalPages, pageable.getPageNumber());
    }

    // ── Suspend / reactivate (D4, AUTH-08) ──────────────────────────────────

    /**
     * Immediate (acceptance criterion 4): revokes every refresh token, ends every open viewer
     * session, and adds the user to the Redis {@code auth:suspended} set so an
     * already-issued access token stops working on its very next request — see
     * {@link SuspendedUsersService}'s javadoc for why Redis and why fail-open.
     */
    @Transactional
    public AdminUserDto suspendUser(Long adminId, Long targetUserId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "A reason is required to suspend a user.");
        }
        if (Objects.equals(adminId, targetUserId)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "You cannot suspend your own account.");
        }
        User target = userRepository.findWithRolesById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", targetUserId));
        if (isAdmin(target)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "You cannot suspend another admin.");
        }

        String trimmedReason = reason.trim();

        target.setAccountStatus(AccountStatus.SUSPENDED);
        userRepository.save(target);

        refreshTokenRepository.revokeAllForUser(target.getId(), Instant.now());
        viewerSessionService.endAllSessionsForUser(target.getId(), ViewerSessionEndReason.REVOKED);
        suspendedUsersService.suspend(target.getId());

        adminAuditService.record(adminId, AdminActionType.SUSPEND_USER, AdminTargetType.USER, target.getId(), trimmedReason);

        log.info("User id={} suspended by admin id={}: {}", target.getId(), adminId, trimmedReason);
        return AdminUserMapper.toDto(target);
    }

    @Transactional
    public AdminUserDto reactivateUser(Long adminId, Long targetUserId) {
        User target = userRepository.findWithRolesById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", targetUserId));

        target.setAccountStatus(AccountStatus.ACTIVE);
        userRepository.save(target);
        suspendedUsersService.reactivate(target.getId());

        adminAuditService.record(adminId, AdminActionType.REACTIVATE_USER, AdminTargetType.USER, target.getId(), null);

        log.info("User id={} reactivated by admin id={}", target.getId(), adminId);
        return AdminUserMapper.toDto(target);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isAdmin(User user) {
        return user.getRoles().stream().anyMatch(r -> r.getRole() == Role.ADMIN);
    }

    private static Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return map;
    }

    /** Same reorder-by-id-list idea as {@code ProductSearchService.reorder}: the JOIN's row
     *  order does not follow {@code ids}'s order. */
    private static List<User> reorder(List<Long> ids, List<User> unordered) {
        Map<Long, User> byId = new LinkedHashMap<>();
        unordered.forEach(u -> byId.put(u.getId(), u));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
