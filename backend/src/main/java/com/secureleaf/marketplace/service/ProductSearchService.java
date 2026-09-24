package com.secureleaf.marketplace.service;

import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.content.repository.DocumentVersionRepository;
import com.secureleaf.marketplace.dto.CategoryDto;
import com.secureleaf.marketplace.dto.ProductDto;
import com.secureleaf.marketplace.dto.ProductFilterInput;
import com.secureleaf.marketplace.dto.ProductPageDto;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.mapper.ProductMapper;
import com.secureleaf.marketplace.repository.CategoryRepository;
import com.secureleaf.marketplace.repository.ProductIdPage;
import com.secureleaf.marketplace.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Public, unauthenticated marketplace read paths.
 *
 * Kept separate from {@link ProductService} (which is already long and owns the
 * creator-authenticated write paths) — this service owns only anonymous browsing:
 * search/filter/sort/paginate and single-product detail.
 *
 * LIVE + not-deleted is enforced IN SQL on every query here, never as an
 * after-the-fact filter in Java — that means there is no code path where a
 * DRAFT/UNPUBLISHED/soft-deleted product can leak into a response (D4).
 */
@Service
@RequiredArgsConstructor
public class ProductSearchService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final DocumentVersionRepository documentVersionRepository;

    @Transactional(readOnly = true)
    public ProductPageDto searchProducts(ProductFilterInput filter, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));

        // Steps 1+2: id page + total count (no collection join — Postgres paginates).
        ProductIdPage idPage = productRepository.searchLiveProductIds(filter, pageable);

        // Step 3: one @EntityGraph fetch of the full entities, then re-order to match
        // idPage.ids() — the JOIN's row order is not the ORDER BY order (D2).
        List<Product> products = idPage.ids().isEmpty()
                ? List.of()
                : reorder(idPage.ids(), productRepository.findAllByIdIn(idPage.ids()));

        List<ProductDto> content = products.stream().map(ProductMapper::toDto).toList();
        int totalPages = idPage.totalElements() == 0
                ? 0
                : (int) Math.ceil(idPage.totalElements() / (double) pageable.getPageSize());

        return new ProductPageDto(content, Math.toIntExact(idPage.totalElements()), totalPages, pageable.getPageNumber());
    }

    /**
     * Single-product detail lookup. A DRAFT/UNPUBLISHED/soft-deleted product (or an id
     * that doesn't exist) throws {@link ResourceNotFoundException} → GraphQL NOT_FOUND —
     * never a 403/FORBIDDEN, which would let a caller distinguish "exists but hidden"
     * from "doesn't exist" and leak the product's existence (D4).
     */
    @Transactional(readOnly = true)
    public ProductDto getLiveProduct(Long id) {
        Product product = productRepository.findByIdAndStatusAndDeletedAtIsNull(id, ProductStatus.LIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        Integer pageCount = documentVersionRepository.findFirstByProductIdOrderByVersionNumberDesc(id)
                .map(dv -> dv.getPageCount())
                .orElse(null);

        return ProductMapper.toDto(product, pageCount);
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> listCategories() {
        return categoryRepository.findAll().stream()
                .map(ProductMapper::toCategoryDto)
                .toList();
    }

    private List<Product> reorder(List<Long> ids, List<Product> unordered) {
        Map<Long, Product> byId = new LinkedHashMap<>();
        unordered.forEach(p -> byId.put(p.getId(), p));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }
}
