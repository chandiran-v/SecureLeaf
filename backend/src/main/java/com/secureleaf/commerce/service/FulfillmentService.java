package com.secureleaf.commerce.service;

import com.secureleaf.commerce.entity.Entitlement;
import com.secureleaf.commerce.entity.Order;
import com.secureleaf.commerce.entity.OrderStatus;
import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.content.entity.DocumentVersion;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.repository.ProductRepository;
import com.secureleaf.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "The order is paid — now deliver what was bought." Shared by the paid path
 * (PaymentCompletionService) and the free path (OrderService, D10), so there is exactly one
 * definition of what fulfilment means.
 *
 * Everything here happens in the caller's transaction ({@code MANDATORY}), which is the whole
 * point: order COMPLETED + payment COMPLETED + audit event + entitlement + sales counter +
 * notification rows either ALL commit or NONE do. There is no state where the buyer was
 * charged, the order says COMPLETED, but no entitlement exists.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FulfillmentService {

    private final EntitlementRepository entitlementRepository;
    private final ProductRepository productRepository;
    private final NotificationService notificationService;

    @Transactional(propagation = Propagation.MANDATORY)
    public Entitlement fulfil(Order order) {
        if (order.getStatus() != OrderStatus.COMPLETED) {
            throw new IllegalStateException("Refusing to fulfil order " + order.getId() + " in status " + order.getStatus());
        }
        Product product = order.getSingleItem().getProduct();

        // PAY-05 / Phase 15 D1: the entitlement pins the version that is CURRENT at purchase time, by
        // the explicit pointer. A v2 still processing is not current, so a buyer never pays for
        // something that can't be opened yet.
        DocumentVersion version = product.getCurrentDocumentVersion();
        if (version == null) {
            throw new IllegalStateException("LIVE product " + product.getId() + " has no current document version");
        }

        Entitlement entitlement = new Entitlement();
        entitlement.setBuyer(order.getBuyer());
        entitlement.setProduct(product);
        entitlement.setDocumentVersion(version);
        entitlement.setOrder(order);
        // saveAndFlush: hit uq_entitlements_buyer_product_active (D6) *here*, inside this method,
        // rather than at commit time where the stack trace would point at nothing useful.
        Entitlement saved = entitlementRepository.saveAndFlush(entitlement);

        productRepository.incrementTotalSales(product.getId());   // D8 — atomic, no lost updates
        notificationService.notifyPurchase(order, product);        // D9 — rows now, side effects after commit

        log.info("Fulfilled order {}: entitlement {} (buyer {}, product {})",
                order.getId(), saved.getId(), order.getBuyer().getId(), product.getId());
        return saved;
    }
}
