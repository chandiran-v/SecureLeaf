package com.secureleaf.commerce.service;

import com.secureleaf.commerce.dto.EntitlementDto;
import com.secureleaf.commerce.dto.OrderDto;
import com.secureleaf.commerce.entity.Entitlement;
import com.secureleaf.commerce.entity.EntitlementStatus;
import com.secureleaf.commerce.entity.Order;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.marketplace.mapper.ProductMapper;

import java.time.ZoneOffset;

/**
 * Entity → DTO mapping for commerce. Same contract as {@link ProductMapper}: call it inside a
 * transaction, because it walks lazy associations (order items, product, tags).
 */
public final class CommerceMapper {

    private CommerceMapper() {}

    public static OrderDto toOrderDto(Order order, String failureReason) {
        return new OrderDto(
                order.getId(),
                order.getStatus().name(),
                order.getTotalAmountPaise(),
                ProductMapper.toDto(order.getSingleItem().getProduct()),
                order.getGatewayOrderId(),
                failureReason,
                order.getCreatedAt() != null ? order.getCreatedAt().atOffset(ZoneOffset.UTC) : null);
    }

    public static EntitlementDto toEntitlementDto(Entitlement e) {
        // Phase 15, D4 — "new edition" = the product's CURRENT version is newer than the one this
        // entitlement reads. Only meaningful for an ACTIVE entitlement; a revoked one has no edition to offer.
        int mine = e.getDocumentVersion().getVersionNumber();
        DocumentVersion current = e.getProduct().getCurrentDocumentVersion();
        boolean newer = e.getStatus() == EntitlementStatus.ACTIVE
                && current != null && current.getVersionNumber() > mine;
        return new EntitlementDto(
                e.getId(),
                ProductMapper.toDto(e.getProduct()),
                e.getGrantedAt().atOffset(ZoneOffset.UTC),
                e.getStatus().name(),
                mine,
                newer);
    }
}
