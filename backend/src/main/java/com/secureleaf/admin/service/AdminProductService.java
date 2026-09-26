package com.secureleaf.admin.service;

import com.secureleaf.admin.dto.AdminProductDto;
import com.secureleaf.admin.dto.AdminProductFilterDto;
import com.secureleaf.admin.dto.AdminProductPageDto;
import com.secureleaf.admin.entity.AdminActionType;
import com.secureleaf.admin.entity.AdminTargetType;
import com.secureleaf.admin.mapper.AdminProductMapper;
import com.secureleaf.admin.repository.AdminProductQueryRepository;
import com.secureleaf.admin.repository.IdPage;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * D5 — {@code adminProducts} search and the {@code takeDownProduct}/{@code restoreProduct}
 * mutations. Post-moderation (see the phase spec's Context): a product goes LIVE on its own
 * after processing, never through an admin approval queue, so the only admin lever here is
 * taking a live product down and, later, restoring it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdminProductService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProductRepository productRepository;
    private final AdminProductQueryRepository adminProductQueryRepository;
    private final AdminAuditService adminAuditService;

    // ── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public AdminProductPageDto adminProducts(AdminProductFilterDto filter, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        IdPage idPage = adminProductQueryRepository.searchProductIds(filter, pageable);

        List<AdminProductDto> content = idPage.ids().isEmpty()
                ? List.of()
                : reorder(idPage.ids(), productRepository.findAllByIdIn(idPage.ids())).stream()
                        .map(AdminProductMapper::toDto)
                        .toList();

        int totalPages = idPage.totalElements() == 0
                ? 0
                : (int) Math.ceil(idPage.totalElements() / (double) pageable.getPageSize());
        return new AdminProductPageDto(content, Math.toIntExact(idPage.totalElements()), totalPages, pageable.getPageNumber());
    }

    // ── Takedown / restore (D5) ──────────────────────────────────────────────

    /**
     * LIVE → UNPUBLISHED, stamped with when and why. Existing buyers are untouched here —
     * entitlements and viewer sessions are a separate concern (revoking them is explicitly out
     * of scope for this phase, see the spec) — this mutation only changes marketplace
     * visibility and blocks the creator's own republishProduct.
     */
    @Transactional
    public AdminProductDto takeDownProduct(Long adminId, Long productId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "A reason is required to take down a product.");
        }
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        if (product.getStatus() != ProductStatus.LIVE) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Product " + productId + " is not LIVE (currently " + product.getStatus() + ") — nothing to take down.");
        }

        String trimmedReason = reason.trim();
        product.setStatus(ProductStatus.UNPUBLISHED);
        product.setTakenDownAt(Instant.now());
        product.setTakedownReason(trimmedReason);
        Product saved = productRepository.save(product);

        adminAuditService.record(adminId, AdminActionType.TAKE_DOWN_PRODUCT, AdminTargetType.PRODUCT, productId, trimmedReason);

        log.info("Product id={} taken down by admin id={}: {}", productId, adminId, trimmedReason);
        return AdminProductMapper.toDto(saved);
    }

    /** UNPUBLISHED → LIVE, only reversing an admin takedown — never a creator's own
     *  unpublishProduct, which leaves {@code takedownReason} null. */
    @Transactional
    public AdminProductDto restoreProduct(Long adminId, Long productId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));
        if (product.getTakedownReason() == null) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Product " + productId + " was not taken down by an admin — nothing to restore.");
        }

        product.setStatus(ProductStatus.LIVE);
        product.setTakenDownAt(null);
        product.setTakedownReason(null);
        Product saved = productRepository.save(product);

        adminAuditService.record(adminId, AdminActionType.RESTORE_PRODUCT, AdminTargetType.PRODUCT, productId, null);

        log.info("Product id={} restored by admin id={}", productId, adminId);
        return AdminProductMapper.toDto(saved);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static List<Product> reorder(List<Long> ids, List<Product> unordered) {
        Map<Long, Product> byId = new LinkedHashMap<>();
        unordered.forEach(p -> byId.put(p.getId(), p));
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
