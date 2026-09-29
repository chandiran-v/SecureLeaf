package com.secureleaf.commerce.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.secureleaf.commerce.dto.OrderDto;
import com.secureleaf.commerce.dto.VerifyPaymentInput;
import com.secureleaf.commerce.entity.Order;
import com.secureleaf.commerce.entity.OrderStatus;
import com.secureleaf.commerce.entity.Payment;
import com.secureleaf.commerce.entity.PaymentStatus;
import com.secureleaf.commerce.gateway.GatewayPayment;
import com.secureleaf.commerce.gateway.PaymentGateway;
import com.secureleaf.commerce.gateway.PaymentGatewayProperties;
import com.secureleaf.commerce.gateway.RazorpaySignatures;
import com.secureleaf.commerce.repository.OrderRepository;
import com.secureleaf.commerce.repository.PaymentEventRepository;
import com.secureleaf.commerce.repository.PaymentRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Applies payment outcomes reported by the gateway — the heart of Phase 4 (D3, D4, D5).
 *
 * TWO MESSENGERS, ONE OUTCOME
 * For every successful payment, Razorpay tells us twice:
 *   1. the browser relays checkout.js's signed response → {@link #verifyCheckout}
 *   2. Razorpay's servers POST a signed webhook          → {@link #recordCapture}
 * (Phase 09B adds a third: the reconciliation sweeper, {@link #recordReconciledCapture}, which
 * asks the gateway what happened to orders whose other two messengers never showed up.)
 * Any can arrive first, several can arrive at the same moment, the browser one may never
 * arrive (tab closed, network drop — D13), and the webhook may arrive more than once.
 * Correctness therefore cannot depend on order or on count. Both paths funnel into
 * {@link #applyCapture}, which:
 *   - takes a row lock on the order (SELECT … FOR UPDATE) — so they never run concurrently,
 *   - returns early if the order is already COMPLETED — so the second one is a no-op.
 * That combination is what "idempotent payment processing" means in practice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCompletionService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final PaymentAuditService paymentAuditService;
    private final FulfillmentService fulfillmentService;
    private final PaymentGatewayProperties gatewayProperties;
    private final PaymentGateway paymentGateway;
    private final ObjectMapper objectMapper;

    /**
     * Messenger 1 — the browser. Never trust the browser's word that it paid: trust the
     * signature, which only Razorpay (holding our key_secret) could have produced for this
     * exact order_id + payment_id pair.
     *
     * One @Transactional method, and it calls applyCapture directly: a call from one method
     * to another *on the same object* doesn't go through Spring's proxy, so an annotation on
     * applyCapture alone would be silently ignored here (the self-invocation trap).
     */
    @Transactional
    public OrderDto verifyCheckout(Long buyerId, VerifyPaymentInput input) {
        // LOCK FIRST, THEN CHECK. Loading the order with a plain SELECT and locking it
        // afterwards looks equivalent but isn't: Hibernate keeps ONE instance per row in the
        // persistence context, so the later SELECT … FOR UPDATE hands back that same instance
        // with the field values from the FIRST read. If a webhook completed the order while we
        // waited for the lock, we'd still see PENDING and fulfil twice. Taking the lock as the
        // first read guarantees the state we check is the latest committed one.
        Order owned = orderRepository.findByGatewayOrderIdForUpdate(input.gatewayOrderId())
                .filter(o -> o.getId().equals(input.orderId()) && o.getBuyer().getId().equals(buyerId))
                // BOLA — not your order (or ids that don't match each other) → NOT_FOUND.
                .orElseThrow(() -> new ResourceNotFoundException("Order", input.orderId()));

        String expected = RazorpaySignatures.checkoutSignature(
                owned.getGatewayOrderId(), input.gatewayPaymentId(), gatewayProperties.keySecret());
        if (!RazorpaySignatures.matches(expected, input.gatewaySignature())) {
            log.warn("Invalid checkout signature for order {} (buyer {})", owned.getId(), buyerId);
            throw new BusinessException(ErrorCode.INVALID_PAYMENT_SIGNATURE, "Payment signature is invalid.");
        }

        // No amount check on this path: the signature binds payment_id to the gateway order,
        // and the gateway order was created by US with the amount WE chose. The webhook path
        // does check the amount, because it arrives with its own amount field.
        String payload = json(Map.of(
                "razorpay_order_id", input.gatewayOrderId(),
                "razorpay_payment_id", input.gatewayPaymentId()));
        Order order = applyCapture(owned.getGatewayOrderId(), input.gatewayPaymentId(), null,
                PaymentAuditService.SOURCE_CHECKOUT_CALLBACK, null, payload);
        return toOrderDto(order);
    }

    /** Messenger 2 — webhook {@code payment.captured}. Signature already verified by the controller. */
    @Transactional
    public void recordCapture(String gatewayOrderId, String gatewayPaymentId, long amountPaise,
                              String providerEventId, String rawPayload) {
        applyCapture(gatewayOrderId, gatewayPaymentId, amountPaise,
                PaymentAuditService.SOURCE_WEBHOOK, providerEventId, rawPayload);
    }

    /**
     * Messenger 3 (Phase 09B D7) — the reconciliation job found a captured payment at the gateway
     * for an order still PENDING here. Same idempotent path as the other two; the amount comes
     * from the gateway, so the amount check applies.
     */
    @Transactional
    public void recordReconciledCapture(String gatewayOrderId, String gatewayPaymentId, long amountPaise) {
        String payload = json(Map.of(
                "razorpay_order_id", gatewayOrderId,
                "razorpay_payment_id", gatewayPaymentId,
                "source", "reconciliation"));
        applyCapture(gatewayOrderId, gatewayPaymentId, amountPaise,
                PaymentAuditService.SOURCE_RECONCILIATION, null, payload);
    }

    /**
     * Webhook {@code payment.authorized} (Phase 09B D4). On accounts without auto-capture, Razorpay
     * only RESERVES the money; nothing is ours until we capture it (and an uncaptured
     * authorization is auto-refunded after a few days). So: capture it. That produces a
     * {@code payment.captured} webhook, which completes the order through the normal path.
     *
     * Deliberately NOT @Transactional: this makes an HTTP call, and holding a database
     * transaction (or a row lock) open across the network is how a slow gateway takes down a
     * connection pool. It only reads, and the capture itself is idempotent at Razorpay.
     */
    public void recordAuthorization(String gatewayOrderId, String gatewayPaymentId, long amountPaise) {
        Order order = orderRepository.findByGatewayOrderId(gatewayOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "gatewayOrderId", gatewayOrderId));
        if (order.getStatus() != OrderStatus.PENDING) {
            log.info("Authorized payment {} for {} order {} — not capturing", gatewayPaymentId, order.getStatus(), order.getId());
            return;
        }
        if (amountPaise != order.getTotalAmountPaise()) {
            log.error("ALERT: authorized amount {} paise != order {} amount {} paise — not capturing",
                    amountPaise, order.getId(), order.getTotalAmountPaise());
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "Authorized amount does not match the order.");
        }
        paymentGateway.capture(gatewayPaymentId, amountPaise);
        log.info("Captured authorized payment {} for order {}", gatewayPaymentId, order.getId());
    }

    /**
     * Reconciliation (D7): a PENDING order nothing was ever captured for, past the expiry window.
     * Locks first and re-checks, because a capture may have landed since the job looked.
     *
     * @return true if the order was expired by this call
     */
    @Transactional
    public boolean expireOrder(String gatewayOrderId) {
        Order order = lockOrder(gatewayOrderId);
        if (order.getStatus() != OrderStatus.PENDING) return false;
        Payment payment = paymentFor(order);
        payment.setFailureReason("expired");
        if (payment.getStatus() != PaymentStatus.FAILED) {
            paymentAuditService.transition(payment, PaymentStatus.FAILED,
                    PaymentAuditService.SOURCE_RECONCILIATION, null, null);
        }
        order.transitionTo(OrderStatus.FAILED);
        log.info("Order {} expired: nothing captured at the gateway", order.getId());
        return true;
    }

    /**
     * Webhook {@code payment.failed}. The card was declined; the ORDER stays PENDING because
     * Razorpay lets the buyer retry on the same order (D5). Only the payment records the failure.
     */
    @Transactional
    public void recordFailure(String gatewayOrderId, String gatewayPaymentId, String reason,
                              String providerEventId, String rawPayload) {
        Order order = lockOrder(gatewayOrderId);
        if (isDuplicateDelivery(providerEventId)) return;

        if (order.getStatus() != OrderStatus.PENDING) {
            // Webhooks can arrive OUT OF ORDER. A failed attempt reported after a later
            // successful one must not un-complete the order. Log it and move on.
            log.info("Ignoring late payment.failed for order {} (already {})", order.getId(), order.getStatus());
            return;
        }
        Payment payment = paymentFor(order);
        payment.setFailureReason(reason);
        paymentAuditService.transition(payment, PaymentStatus.FAILED,
                PaymentAuditService.SOURCE_WEBHOOK, providerEventId, rawPayload);
        log.info("Payment {} for order {} declined: {}", gatewayPaymentId, order.getId(), reason);
    }

    // ── The single completion path ───────────────────────────────────────────

    /**
     * Must run inside a transaction (both callers are @Transactional) — the row lock taken
     * by lockOrder is held until that transaction commits.
     *
     * @param expectedAmountPaise the amount the gateway says it captured, or null if the
     *                            caller has no independent amount (checkout callback)
     */
    private Order applyCapture(String gatewayOrderId, String gatewayPaymentId, Long expectedAmountPaise,
                               String source, String providerEventId, String rawPayload) {
        Order order = lockOrder(gatewayOrderId);            // ← serializes browser vs webhook vs duplicate webhook

        if (isDuplicateDelivery(providerEventId)) return order;

        if (order.getStatus() == OrderStatus.COMPLETED) {    // ← the other messenger got here first
            log.info("Order {} already completed — {} is a no-op", order.getId(), source);
            return order;
        }
        if (order.getStatus() != OrderStatus.PENDING) {
            // Money was captured for an order we had closed (e.g. superseded after a price
            // change). Don't grant access at a price we no longer honour — this needs a refund.
            log.error("ALERT: capture {} received for {} order {} — manual refund required",
                    gatewayPaymentId, order.getStatus(), order.getId());
            throw new BusinessException(ErrorCode.ORDER_NOT_PAYABLE, "Order " + order.getId() + " is not payable.");
        }
        if (expectedAmountPaise != null && expectedAmountPaise.longValue() != order.getTotalAmountPaise()) {
            // Never grant access based on "a payment happened" — check it was for the right amount.
            log.error("ALERT: amount mismatch on order {}: captured {} paise, order is {} paise",
                    order.getId(), expectedAmountPaise, order.getTotalAmountPaise());
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, "Captured amount does not match the order.");
        }

        Payment payment = paymentFor(order);
        payment.setProviderPaymentId(gatewayPaymentId);
        payment.setFailureReason(null);
        recordGatewayFees(payment, gatewayPaymentId);
        paymentAuditService.transition(payment, PaymentStatus.COMPLETED, source, providerEventId, rawPayload);

        order.transitionTo(OrderStatus.COMPLETED);
        fulfillmentService.fulfil(order);                   // entitlement + sales counter + notifications

        log.info("Order {} COMPLETED via {} (payment {})", order.getId(), source, gatewayPaymentId);
        return order;
    }

    /**
     * D5 — remember what the gateway charged us for this payment. Best effort: a failed lookup must
     * never block a buyer who has already paid from getting their entitlement, so on any failure
     * the fee columns stay NULL ("unknown") and we log it.
     *
     * Runs inside the order's row lock, so it is bounded by the gateway client's timeouts
     * (3 s connect / 10 s read) and only ever runs on the ONE call that actually completes the order.
     */
    private void recordGatewayFees(Payment payment, String gatewayPaymentId) {
        try {
            GatewayPayment fetched = paymentGateway.fetchPayment(gatewayPaymentId);
            payment.setGatewayFeePaise(fetched.feePaise());
            payment.setGatewayTaxPaise(fetched.taxPaise());
        } catch (RuntimeException e) {
            log.warn("Could not fetch gateway fee for payment {}: {}", gatewayPaymentId, e.getMessage());
        }
    }

    private Order lockOrder(String gatewayOrderId) {
        return orderRepository.findByGatewayOrderIdForUpdate(gatewayOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", "gatewayOrderId", gatewayOrderId));
    }

    /**
     * Checked AFTER taking the lock, deliberately: two copies of the same webhook arriving
     * together both queue on the lock; the second one then sees the first one's committed
     * event row. Checked before the lock, both would see "not processed yet". (The UNIQUE
     * constraint on provider_event_id is the final backstop either way.)
     */
    private boolean isDuplicateDelivery(String providerEventId) {
        if (providerEventId != null && paymentEventRepository.existsByProviderEventId(providerEventId)) {
            log.info("Duplicate webhook delivery {} — already processed", providerEventId);
            return true;
        }
        return false;
    }

    private Payment paymentFor(Order order) {
        return paymentRepository.findByOrderId(order.getId())
                .orElseThrow(() -> new IllegalStateException("Paid order " + order.getId() + " has no payment row"));
    }

    private OrderDto toOrderDto(Order order) {
        String failureReason = paymentRepository.findByOrderId(order.getId()).map(Payment::getFailureReason).orElse(null);
        return CommerceMapper.toOrderDto(order, failureReason);
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
