package com.secureleaf.commerce.repository;

import com.secureleaf.commerce.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    /** Webhooks such as refund.processed identify the payment, not our order (Phase 09B D6). */
    Optional<Payment> findByProviderPaymentId(String providerPaymentId);

    /** Just the order id, so the caller can take the ORDER lock before loading the payment. */
    @Query("select p.order.id from Payment p where p.providerPaymentId = :providerPaymentId")
    Optional<Long> findOrderIdByProviderPaymentId(@Param("providerPaymentId") String providerPaymentId);
}
