package com.secureleaf.commerce.repository;

import com.secureleaf.commerce.entity.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Append-only audit log (D5). Deliberately exposes only inserts (inherited save) and
 * reads — and even if someone called delete(), the V4 trigger would reject it.
 */
public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    /** Webhook dedup (D4): has this provider event id already been processed? */
    boolean existsByProviderEventId(String providerEventId);

    List<PaymentEvent> findByPaymentIdOrderByIdAsc(Long paymentId);
}
