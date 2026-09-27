package com.secureleaf.marketplace.mapper;

import com.secureleaf.marketplace.dto.CategoryDto;
import com.secureleaf.marketplace.dto.CreatorSummaryDto;
import com.secureleaf.marketplace.dto.ProductDto;
import com.secureleaf.marketplace.dto.ProductPageDto;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductTag;
import org.springframework.data.domain.Page;

import java.time.ZoneOffset;
import java.util.List;

/**
 * Static mapping utilities between JPA entities and DTOs.
 *
 * These methods MUST be called from inside a @Transactional service method.
 * The reason: Product.tags, Product.creator.roles and Product.category are
 * lazy associations. Calling product.getTags() outside a Hibernate session
 * throws LazyInitializationException — the same bug we fixed in AuthService.getUserById.
 *
 * Declaring the mapper static (not @Component) is a deliberate choice: it makes
 * the transactional boundary requirement visible at the call site — the caller
 * must be in a transaction, or the mapping will fail loudly.
 */
public class ProductMapper {

    private ProductMapper() {} // utility class — no instantiation

    public static ProductDto toDto(Product product) {
        return toDto(product, null);
    }

    /**
     * @param pageCount the product's current document version page count, or null when
     *                  the caller hasn't fetched it (e.g. list views that don't need it).
     */
    public static ProductDto toDto(Product product, Integer pageCount) {
        if (product == null) return null;

        List<String> tags = product.getTags().stream()
                .map(ProductTag::getTag)
                .sorted()
                .toList();

        return new ProductDto(
                product.getId(),
                product.getTitle(),
                product.getDescription(),
                product.getPricePaise(),
                product.getStatus().name(),
                toCreatorSummaryDto(product),
                toCategoryDto(product.getCategory()),
                tags,
                product.getCoverImageUrl(),       // thumbnailKey maps to coverImageUrl — a raw MinIO key
                product.getAverageRating() != null ? product.getAverageRating().doubleValue() : null,
                product.getTotalSales(),
                product.getFreePreviewPages(),
                pageCount,
                product.getCreatedAt() != null ? product.getCreatedAt().atOffset(ZoneOffset.UTC) : null,
                product.getTakedownReason()
        );
    }

    public static CreatorSummaryDto toCreatorSummaryDto(Product product) {
        return new CreatorSummaryDto(product.getCreator().getId(), product.getCreator().getDisplayName());
    }

    public static CategoryDto toCategoryDto(Category category) {
        if (category == null) return null;
        return new CategoryDto(category.getId(), category.getName(), category.getSlug());
    }

    public static ProductPageDto toPageDto(Page<Product> page) {
        return new ProductPageDto(
                page.getContent().stream().map(ProductMapper::toDto).toList(),
                Math.toIntExact(page.getTotalElements()),
                page.getTotalPages(),
                page.getNumber()
        );
    }
}
