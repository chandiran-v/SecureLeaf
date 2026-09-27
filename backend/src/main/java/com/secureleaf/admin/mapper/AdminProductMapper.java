package com.secureleaf.admin.mapper;

import com.secureleaf.admin.dto.AdminProductDto;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.mapper.ProductMapper;

import java.time.ZoneOffset;

public class AdminProductMapper {

    private AdminProductMapper() {}

    public static AdminProductDto toDto(Product product) {
        if (product == null) return null;
        return new AdminProductDto(
                product.getId(),
                product.getTitle(),
                product.getStatus().name(),
                ProductMapper.toCreatorSummaryDto(product),
                product.getPricePaise(),
                product.getTotalSales(),
                product.getTakenDownAt() != null ? product.getTakenDownAt().atOffset(ZoneOffset.UTC) : null,
                product.getTakedownReason(),
                product.getCreatedAt() != null ? product.getCreatedAt().atOffset(ZoneOffset.UTC) : null
        );
    }
}
