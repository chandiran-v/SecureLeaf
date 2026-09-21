package com.secureleaf.marketplace.service;

import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.marketplace.dto.CreateProductInput;
import com.secureleaf.marketplace.dto.ProductDto;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.entity.ProductTag;
import com.secureleaf.marketplace.mapper.ProductMapper;
import com.secureleaf.marketplace.repository.CategoryRepository;
import com.secureleaf.marketplace.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Core product management service for creator operations.
 *
 * Security model (UPLOAD-09, requirements.md:166):
 *   @PreAuthorize("hasRole('CREATOR')") on the resolver checks the role.
 *   assertOwnership() inside each mutating method checks the specific record.
 *
 * These are two separate, independent checks:
 *   - Role check: "Is this user a creator at all?"
 *   - Ownership check: "Does this creator own *this specific* product?"
 *
 * Without assertOwnership, any creator could unpublish or delete another
 * creator's products — a BOLA (Broken Object Level Authorization) vulnerability.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final UserRepository userRepository;

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");
    private static final long MAX_PRICE_PAISE = 2_000_000_00L; // ₹20,00,000 — sanity cap

    // ── Create ───────────────────────────────────────────────────────────────

    @Transactional
    public ProductDto createProduct(CreateProductInput input, Long creatorId) {
        validateProductInput(input);

        User creator = userRepository.findById(creatorId)
                .orElseThrow(() -> new ResourceNotFoundException("User", creatorId));

        Category category = categoryRepository.findById(input.categoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Category", input.categoryId()));

        Product product = new Product();
        product.setCreator(creator);
        product.setCategory(category);
        product.setTitle(input.title().trim());
        product.setDescription(input.description().trim());
        product.setPricePaise((long) input.pricePaise());
        product.setFreePreviewPages(input.freePreviewPages());
        product.setStatus(ProductStatus.DRAFT);
        product.setSlug(generateUniqueSlug(input.title()));

        // Flush first so Product gets its IDENTITY-generated id before ProductTag
        // rows are cascaded — ProductTag's composite @Id includes `product`, and
        // Hibernate needs that id populated to build the child's EntityKey.
        // Cascading tags in the same insert as the parent throws
        // "AssertionFailure: null identifier" with IDENTITY generation.
        final Product saved = productRepository.saveAndFlush(product);

        // Tags: lowercase and deduplicate (DB CHECK constraint enforces lowercase)
        if (input.tags() != null) {
            input.tags().stream()
                    .filter(t -> t != null && !t.isBlank())
                    .map(t -> t.toLowerCase(Locale.ROOT).trim())
                    .distinct()
                    .forEach(tag -> {
                        ProductTag pt = new ProductTag();
                        pt.setProduct(saved);
                        pt.setTag(tag);
                        saved.getTags().add(pt);
                    });
            productRepository.save(saved);
        }

        log.info("Product created: id={}, creatorId={}, slug={}", saved.getId(), creatorId, saved.getSlug());
        return ProductMapper.toDto(saved);
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<ProductDto> myProducts(Long creatorId) {
        return productRepository.findByCreatorIdAndDeletedAtIsNull(creatorId)
                .stream()
                .map(ProductMapper::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public Product getProductById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    // ── Unpublish ────────────────────────────────────────────────────────────

    @Transactional
    public ProductDto unpublishProduct(Long id, Long creatorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        assertOwnership(product, creatorId);

        product.setStatus(ProductStatus.UNPUBLISHED);
        log.info("Product unpublished: id={}, creatorId={}", id, creatorId);
        return ProductMapper.toDto(productRepository.save(product));
    }

    // ── Delete (soft) ────────────────────────────────────────────────────────

    /**
     * Soft deletes the product by setting deletedAt.
     *
     * Why soft delete?
     * Buyers who purchased the product must retain access to it (UPLOAD-09).
     * Entitlements hold a FK to the product; hard-deleting would orphan them
     * or require cascading deletion that wipes buyer history.
     * Setting deletedAt hides the product from the marketplace while keeping
     * all related rows intact.
     */
    @Transactional
    public void deleteProduct(Long id, Long creatorId) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));

        assertOwnership(product, creatorId);

        product.setDeletedAt(Instant.now());
        productRepository.save(product);
        log.info("Product soft-deleted: id={}, creatorId={}", id, creatorId);
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Object-level authorization check.
     *
     * This is the most important security check in this service.
     * @PreAuthorize("hasRole('CREATOR')") only confirms the user IS a creator.
     * This method confirms the creator owns THIS SPECIFIC product.
     *
     * Without it, creator A could call unpublishProduct(creatorBProductId, creatorAId)
     * and successfully take down a competitor's product. That is BOLA (Broken Object
     * Level Authorization) — OWASP API Security #1.
     */
    private void assertOwnership(Product product, Long creatorId) {
        if (!product.getCreator().getId().equals(creatorId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED,
                    "You do not have permission to modify this product.");
        }
    }

    /**
     * Derives a URL-safe slug from the title and appends a numeric suffix if needed
     * to guarantee uniqueness (e.g., "my-book-2" if "my-book" already exists).
     *
     * Slugs are stored normalized (lowercase, no diacritics, hyphens for spaces).
     * Example: "Café au Lait ☕" → "cafe-au-lait"
     */
    private String generateUniqueSlug(String title) {
        String base = Normalizer.normalize(title.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")    // strip diacritics
                .trim();
        base = NON_ALPHANUMERIC.matcher(base).replaceAll("-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");    // trim leading/trailing hyphens
        if (base.length() > 250) base = base.substring(0, 250);

        String slug = base;
        int suffix = 2;
        while (productRepository.existsBySlug(slug)) {
            slug = base + "-" + suffix++;
        }
        return slug;
    }

    private void validateProductInput(CreateProductInput input) {
        if (input.pricePaise() < 0) {
            throw new BusinessException(ErrorCode.INVALID_FILE,
                    "Price cannot be negative.");
        }
        if (input.pricePaise() > MAX_PRICE_PAISE) {
            throw new BusinessException(ErrorCode.INVALID_FILE,
                    "Price exceeds maximum allowed value.");
        }
        if (input.freePreviewPages() < 0) {
            throw new BusinessException(ErrorCode.INVALID_FILE,
                    "freePreviewPages cannot be negative.");
        }
        if (input.title() == null || input.title().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_FILE, "Title is required.");
        }
    }
}
