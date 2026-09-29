package com.secureleaf.commerce.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.secureleaf.admin.entity.AdminActionType;
import com.secureleaf.admin.entity.AdminTargetType;
import com.secureleaf.admin.service.AdminAuditService;
import com.secureleaf.commerce.dto.OrderDto;
import com.secureleaf.commerce.entity.Entitlement;
import com.secureleaf.commerce.entity.EntitlementStatus;
import com.secureleaf.commerce.entity.Order;
import com.secureleaf.commerce.entity.OrderStatus;
import com.secureleaf.commerce.entity.Payment;
import com.secureleaf.commerce.entity.PaymentStatus;
import com.secureleaf.commerce.gateway.GatewayRefund;
import com.secureleaf.commerce.gateway.PaymentGateway;
import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.commerce.repository.OrderRepository;
import com.secureleaf.commerce.repository.PaymentEventRepository;
import com.secureleaf.commerce.repository.PaymentRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.repository.ProductRepository;
import com.secureleaf.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

/**
 * Refunds (Phase 09B D6) — a refund is a STATE TRANSITION, not a delete: order COMPLETED → REFUNDED,
 * payment COMPLETED → REFUNDED, entitlement ACTIVE → REVOKED. The history stays; only the state
 * (and what the buyer can open) changes.
 *
 * TWO ENTRANCES, ONE EFFECT (same idea as the payment-completion path)
 *   1. an admin presses Refund → {@link #refundOrder}: we call the gateway, then apply the effect;
 *   2. Razorpay tells us a refund was processed → {@link #recordRefundProcessed}: covers refunds
 *      made from the Razorpay dashboard, and the case where our call succeeded but our commit failed.
 * Both funnel into {@link #applyRefund} under the order's row lock, and both no-op if the order is
 * already REFUNDED — so a duplicate or late webhook changes nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final EntitlementRepository entitlementRepository;
    private final ProductRepository productRepository;
    private final PaymentAuditService paymentAuditService;
    private final AdminAuditService adminAuditService;
    private final NotificationService notificationService;
    private final PaymentGateway paymentGateway;
    private final ObjectMapper objectMapper;

    /**
     * Admin-triggered full refund. The gateway call happens INSIDE the transaction, under the order
     * lock, on purpose: it is what stops two admins (or an admin and a webhook) refunding the same
     * order twice. If the gateway rejects the call, the exception rolls everything back and the
     * order is untouched.
     */
    @Transactional
    public OrderDto refundOrder(Long adminId, Long orderId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "A reason is required to refund an order.");
        }
        String trimmedReason = reason.trim();

        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Order " + orderId + " is " + order.getStatus() + " — only a COMPLETED order can be refunded.");
        }
        Payment payment = paymentRepository.findByOrderId(orderId)
                .filter(p -> p.getProviderPaymentId() != null)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_INPUT,
                        "Order " + orderId + " has no gateway payment to refund (free order?)."));

        GatewayRefund refund = paymentGateway.refund(payment.getProviderPaymentId(), payment.getAmountPaise(),
                Map.of("orderId", String.valueOf(orderId), "reason", abbreviate(trimmedReason)));

        applyRefund(order, payment, trimmedReason, refund.id(), PaymentAuditService.SOURCE_ADMIN_REFUND, null,
                json(Map.of("refund_id", refund.id(), "payment_id", payment.getProviderPaymentId(), "reason", trimmedReason)));
        adminAuditService.record(adminId, AdminActionType.REFUND_ORDER, AdminTargetType.ORDER, orderId, trimmedReason);

        log.info("Order {} refunded by admin id={} (refund {})", orderId, adminId, refund.id());
        return CommerceMapper.toOrderDto(order, payment.getFailureReason());
    }

    /** Webhook {@code refund.processed}. Signature already verified by the controller. */
    @Transactional
    public void recordRefundProcessed(String gatewayPaymentId, String refundId, String providerEventId, String rawPayload) {
        // Order lock FIRST, then read everything else — see PaymentCompletionService.verifyCheckout.
        Long orderId = paymentRepository.findOrderIdByProviderPaymentId(gatewayPaymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "providerPaymentId", gatewayPaymentId));
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));

        if (providerEventId != null && paymentEventRepository.existsByProviderEventId(providerEventId)) {
            log.info("Duplicate refund webhook {} — already processed", providerEventId);
            return;
        }
        if (order.getStatus() == OrderStatus.REFUNDED) {
            log.info("Order {} already refunded — refund webhook {} is a no-op", orderId, providerEventId);
            return;
        }
        if (order.getStatus() != OrderStatus.COMPLETED) {
            log.warn("Ignoring refund webhook {} for {} order {}", providerEventId, order.getStatus(), orderId);
            return;
        }
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("Completed order " + orderId + " has no payment row"));
        // No admin acted (the refund came from the gateway's own dashboard), so no admin_actions row.
        applyRefund(order, payment, "Refunded at the payment provider", refundId,
                PaymentAuditService.SOURCE_WEBHOOK, providerEventId, rawPayload);
    }

    /** Must run inside a transaction, with the order row locked by the caller. */
    private void applyRefund(Order order, Payment payment, String reason, String refundId,
                             String source, String providerEventId, String rawPayload) {
        paymentAuditService.transition(payment, PaymentStatus.REFUNDED, source, providerEventId, rawPayload);
        payment.setRefundId(refundId);
        payment.setRefundReason(reason);
        payment.setRefundedAt(Instant.now());

        order.transitionTo(OrderStatus.REFUNDED);

        Product product = order.getSingleItem().getProduct();
        entitlementRepository.findByOrderId(order.getId())
                .filter(e -> e.getStatus() == EntitlementStatus.ACTIVE)
                .ifPresent(e -> revoke(e, reason));
        productRepository.decrementTotalSales(product.getId());
        notificationService.notifyRefund(order, product, reason);
    }

    private static void revoke(Entitlement entitlement, String reason) {
        entitlement.setStatus(EntitlementStatus.REVOKED);
        entitlement.setRevokedAt(Instant.now());
        entitlement.setRevocationReason("Refunded: " + reason);
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
