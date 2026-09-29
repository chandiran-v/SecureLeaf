package com.secureleaf.commerce.service;

import com.secureleaf.commerce.PaymentProperties;
import com.secureleaf.commerce.entity.Order;
import com.secureleaf.commerce.entity.OrderStatus;
import com.secureleaf.commerce.gateway.GatewayPayment;
import com.secureleaf.commerce.gateway.PaymentGateway;
import com.secureleaf.commerce.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Reconciliation (Phase 09B D7) — closes the weakest point of the Phase 4 design: "if both the
 * browser's callback AND the webhook go missing, a paid order stays PENDING forever."
 *
 * Every 10 minutes: for each order still PENDING after {@code minAgeMinutes}, ask the gateway
 * what happened to it.
 *   - a captured payment (or an authorized one we capture now) → complete the order through the SAME
 *     idempotent path the browser handler and the webhook use;
 *   - nothing captured and the order is older than the expiry window (24 h) → FAILED, "expired".
 *
 * The job has no locking of its own on purpose: {@link PaymentCompletionService} takes the order
 * row lock and re-checks the status, so it can run while a webhook is arriving, or on two
 * instances at once, and still produce exactly one entitlement.
 *
 * The timer is switched off in tests ({@code payment.reconciliation.enabled=false}); they call
 * {@link #reconcile()} directly, which always runs.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationJob {

    private static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentCompletionService paymentCompletionService;
    private final PaymentProperties paymentProperties;

    @Scheduled(initialDelayString = "${payment.reconciliation.initial-delay-ms:60000}",
               fixedDelayString = "${payment.reconciliation.interval-ms:600000}")
    public void scheduledRun() {
        if (!paymentProperties.reconciliation().enabled()) return;
        reconcile();
    }

    /** One sweep. Returns how many orders it changed (completed or expired). */
    public int reconcile() {
        PaymentProperties.Reconciliation cfg = paymentProperties.reconciliation();
        Instant now = Instant.now();
        List<Order> candidates = orderRepository.findStaleByStatus(
                OrderStatus.PENDING, now.minus(Duration.ofMinutes(cfg.minAgeMinutes())), PageRequest.of(0, BATCH_SIZE));

        int changed = 0;
        for (Order order : candidates) {
            try {
                if (reconcileOne(order, now.minus(Duration.ofMinutes(cfg.expireAfterMinutes())))) changed++;
            } catch (RuntimeException e) {
                // One bad order (gateway hiccup, amount mismatch…) must not stop the rest of the sweep.
                log.warn("Reconciliation of order {} failed: {}", order.getId(), e.getMessage());
            }
        }
        if (!candidates.isEmpty()) {
            log.info("Reconciliation: looked at {} pending order(s), changed {}", candidates.size(), changed);
        }
        return changed;
    }

    private boolean reconcileOne(Order order, Instant expireBefore) {
        String gatewayOrderId = order.getGatewayOrderId();
        List<GatewayPayment> attempts = paymentGateway.fetchOrderPayments(gatewayOrderId);

        Optional<GatewayPayment> captured = attempts.stream().filter(GatewayPayment::isCaptured).findFirst();
        if (captured.isEmpty()) {
            // An authorized-but-never-captured payment (D4's webhook was lost): capture it now.
            captured = attempts.stream().filter(GatewayPayment::isAuthorized).findFirst()
                    .map(p -> paymentGateway.capture(p.id(), p.amountPaise()))
                    .filter(GatewayPayment::isCaptured);
        }
        if (captured.isPresent()) {
            GatewayPayment payment = captured.get();
            paymentCompletionService.recordReconciledCapture(gatewayOrderId, payment.id(), payment.amountPaise());
            return true;
        }
        if (order.getCreatedAt().isBefore(expireBefore)) {
            return paymentCompletionService.expireOrder(gatewayOrderId);
        }
        return false;
    }
}
