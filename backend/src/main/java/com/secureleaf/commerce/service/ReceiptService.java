package com.secureleaf.commerce.service;

import com.secureleaf.commerce.dto.ReceiptDto;
import com.secureleaf.commerce.entity.Order;
import com.secureleaf.commerce.entity.OrderStatus;
import com.secureleaf.commerce.repository.OrderRepository;
import com.secureleaf.commerce.repository.PaymentRepository;
import com.secureleaf.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;

/**
 * Printable buyer receipt (Phase 09C D5). Buyer-scoped like {@code OrderService.getOrderForBuyer}:
 * the buyer id is in the WHERE clause, so someone else's order id is simply "not found" (BOLA → 404,
 * never a 403 that would confirm the order exists).
 */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PlatformInfoService platformInfoService;

    @Transactional(readOnly = true)
    public ReceiptDto receipt(Long orderId, Long buyerId) {
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                // A receipt proves a purchase happened, so unpaid/abandoned orders have none.
                .filter(o -> o.getStatus() == OrderStatus.COMPLETED || o.getStatus() == OrderStatus.REFUNDED)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        String paymentId = paymentRepository.findByOrderId(orderId)
                .map(p -> p.getProviderPaymentId()).orElse(null);
        var item = order.getSingleItem();
        var when = order.getCompletedAt() != null ? order.getCompletedAt() : order.getCreatedAt();
        return new ReceiptDto(
                order.getId(),
                order.getStatus().name(),
                when.atOffset(ZoneOffset.UTC),
                item.getProduct().getTitle(),
                item.getProduct().getCreator().getDisplayName(),
                order.getTotalAmountPaise(),
                mask(paymentId),
                platformInfoService.platformInfo().paymentMode());
    }

    /** "pay_Abc123XYZ789" → "pay_••••Z789": enough to quote to support, useless to anyone else. */
    static String mask(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) return null;
        if (paymentId.length() <= 8) return "••••";
        int underscore = paymentId.indexOf('_');
        String prefix = underscore > 0 && underscore < 6 ? paymentId.substring(0, underscore + 1) : "";
        return prefix + "••••" + paymentId.substring(paymentId.length() - 4);
    }
}
