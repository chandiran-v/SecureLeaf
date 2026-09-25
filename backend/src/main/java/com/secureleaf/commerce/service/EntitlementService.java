package com.secureleaf.commerce.service;

import com.secureleaf.commerce.dto.EntitlementDto;
import com.secureleaf.commerce.entity.EntitlementStatus;
import com.secureleaf.commerce.repository.EntitlementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Read side of entitlements: "what does this buyer own?" The Phase 5 viewer will ask the
 * same question (VIEW-12) before serving a single tile.
 */
@Service
@RequiredArgsConstructor
public class EntitlementService {

    private final EntitlementRepository entitlementRepository;

    /**
     * PAY-09 / Phase 6 D1 — the buyer's whole library, newest purchase first: ACTIVE, REVOKED and
     * EXPIRED entitlements all included, so a revoked purchase still shows up (with that status)
     * instead of disappearing. What a REVOKED/EXPIRED row can no longer do is open the viewer —
     * that's enforced by ViewerSessionService.startViewerSession's ACTIVE-only lookup, not by
     * hiding the row here.
     */
    @Transactional(readOnly = true)
    public List<EntitlementDto> myLibrary(Long buyerId) {
        return entitlementRepository.findByBuyerIdOrderByGrantedAtDesc(buyerId)
                .stream()
                .map(CommerceMapper::toEntitlementDto)
                .toList();
    }

    /** Which of these products does the buyer own? One query regardless of list size (D11). */
    @Transactional(readOnly = true)
    public Set<Long> ownedProductIds(Long buyerId, Collection<Long> productIds) {
        if (productIds.isEmpty()) return Set.of();
        return new HashSet<>(entitlementRepository.findOwnedProductIds(buyerId, productIds));
    }
}
