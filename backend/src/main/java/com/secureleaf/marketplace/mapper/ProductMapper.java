package com.secureleaf.marketplace.mapper;

import com.secureleaf.auth.mapper.UserMapper;
import com.secureleaf.marketplace.dto.CategoryDto;
import com.secureleaf.marketplace.dto.ProductDto;
import com.secureleaf.marketplace.entity.Category;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductTag;

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
                UserMapper.toDto(product.getCreator()),
                toCategoryDto(product.getCategory()),
                tags,
                product.getCoverImageUrl(),       // thumbnailUrl maps to coverImageUrl
                product.getAverageRating() != null ? product.getAverageRating().doubleValue() : null,
                product.getTotalSales(),
                product.getFreePreviewPages(),
                product.getCreatedAt() != null ? product.getCreatedAt().atOffset(ZoneOffset.UTC) : null
        );
    }

    public static CategoryDto toCategoryDto(Category category) {
        if (category == null) return null;
        return new CategoryDto(category.getId(), category.getName(), category.getSlug());
    }
}
