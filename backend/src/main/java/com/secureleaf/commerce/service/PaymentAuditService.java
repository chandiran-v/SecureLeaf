package com.secureleaf.commerce.service;

import com.secureleaf.commerce.entity.Payment;
import com.secureleaf.commerce.entity.PaymentEvent;
import com.secureleaf.commerce.entity.PaymentStatus;
import com.secureleaf.commerce.repository.PaymentEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way a payment's status changes (D5, PAY-06).
 *
 * Pairing "change the status" and "append the audit event" in one method means there is no
 * code path that does one without the other — the audit log can't silently drift from reality.
 * Combined with the V4 trigger (no UPDATE/DELETE on payment_events), the log is a complete,
 * tamper-resistant history: you can replay it to explain every payment's current state.
 */
@Service
@RequiredArgsConstructor
public class PaymentAuditService {

    public static final String SOURCE_CHECKOUT = "CHECKOUT";                   // initiateOrder created it
    public static final String SOURCE_CHECKOUT_CALLBACK = "CHECKOUT_CALLBACK"; // browser verifyPayment
    public static final String SOURCE_WEBHOOK = "WEBHOOK";                     // gateway → us
    public static final String SOURCE_SYSTEM = "SYSTEM";                       // our own housekeeping

    private final PaymentEventRepository paymentEventRepository;

    /** First event of every payment: (null) → PENDING. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCreated(Payment payment, String source) {
        append(payment, null, PaymentStatus.PENDING, source, null, null);
    }

    /**
     * Validates the transition against the state machine, applies it, and appends the event.
     *
     * @param providerEventId the gateway's event id (webhooks only) — UNIQUE in the table, so
     *                        the same webhook can never be recorded twice
     * @param rawPayload      the gateway's JSON, kept verbatim for disputes and debugging
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void transition(Payment payment, PaymentStatus to, String source,
                           String providerEventId, String rawPayload) {
        PaymentStatus from = payment.getStatus();
        payment.transitionTo(to);   // throws INVALID_STATE_TRANSITION on an illegal move
        append(payment, from, to, source, providerEventId, rawPayload);
    }

    private void append(Payment payment, PaymentStatus from, PaymentStatus to, String source,
                        String providerEventId, String rawPayload) {
        PaymentEvent event = new PaymentEvent();
        event.setPayment(payment);
        event.setFromStatus(from);
        event.setToStatus(to);
        event.setEventSource(source);
        event.setProviderEventId(providerEventId);
        event.setRawPayload(rawPayload);
        paymentEventRepository.save(event);
    }
}
