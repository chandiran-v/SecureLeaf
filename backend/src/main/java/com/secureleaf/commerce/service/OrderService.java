package com.secureleaf.commerce.service;

import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.commerce.CommerceProperties;
import com.secureleaf.commerce.dto.CreatorEarningsDto;
import com.secureleaf.commerce.dto.InitiateOrderPayload;
import com.secureleaf.commerce.dto.OrderDto;
import com.secureleaf.commerce.entity.EntitlementStatus;
import com.secureleaf.commerce.entity.Order;
import com.secureleaf.commerce.entity.OrderItem;
import com.secureleaf.commerce.entity.OrderStatus;
import com.secureleaf.commerce.entity.Payment;
import com.secureleaf.commerce.entity.PaymentStatus;
import com.secureleaf.commerce.gateway.PaymentGateway;
import com.secureleaf.commerce.repository.EntitlementRepository;
import com.secureleaf.commerce.repository.OrderItemRepository;
import com.secureleaf.commerce.repository.OrderRepository;
import com.secureleaf.commerce.repository.PaymentRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.marketplace.entity.ProductStatus;
import com.secureleaf.marketplace.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout: turning "I want to buy product X" into an order the gateway can charge.
 *
 * Security model (same two-layer shape as ProductService):
 *   1. {@code @PreAuthorize("isAuthenticated()")} on the resolver — only logged-in users buy.
 *   2. Object-level checks here — product is LIVE, buyer isn't its creator, buyer doesn't
 *      already own it, and every order lookup is scoped to the buyer (BOLA → NOT_FOUND).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 255;

    private final UserRepository userRepository;
    private final ProductRepository productRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final PaymentRepository paymentRepository;
    private final EntitlementRepository entitlementRepository;
    private final PaymentGateway paymentGateway;
    private final PaymentAuditService paymentAuditService;
    private final FulfillmentService fulfillmentService;
    private final CommerceProperties commerceProperties;

    /**
     * PAY-03 — create (or return) the order for a purchase intent. Safe to call any number of
     * times: see the three idempotency layers (D2) below.
     */
    @Transactional
    public InitiateOrderPayload initiateOrder(Long buyerId, Long productId, String idempotencyKey) {
        validateIdempotencyKey(idempotencyKey);

        // Layer 3 first, because it protects layers 1 and 2: a per-buyer row lock. Everything
        // below is "check, then act"; without the lock, two concurrent calls could both pass
        // the checks before either inserts. With it, the second call waits here until the
        // first commits — and then sees the first one's order.
        User buyer = userRepository.findByIdForUpdate(buyerId)
                .orElseThrow(() -> new ResourceNotFoundException("User", buyerId));

        // Layer 1 — exact replay of a request we've already processed (Stripe Idempotency-Key).
        var replay = orderRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            Order order = replay.get();
            boolean sameRequest = order.getBuyer().getId().equals(buyerId)
                    && order.getSingleItem().getProduct().getId().equals(productId);
            if (!sameRequest) {
                // A key is a promise that it names ONE request. Reusing it for a different
                // product (or it belongs to someone else) is a client bug — refuse loudly
                // rather than return someone else's order.
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        "This idempotency key was already used for a different purchase.");
            }
            log.info("initiateOrder replay: key={}, order={}", idempotencyKey, order.getId());
            return toPayload(order);
        }

        Product product = productRepository.findByIdAndStatusAndDeletedAtIsNull(productId, ProductStatus.LIVE)
                .orElseThrow(() -> new ResourceNotFoundException("Product", productId));

        if (product.getCreator().getId().equals(buyerId)) {
            throw new BusinessException(ErrorCode.CANNOT_BUY_OWN_PRODUCT, "You can't buy your own product.");
        }
        if (entitlementRepository.existsByBuyerIdAndProductIdAndStatus(buyerId, productId, EntitlementStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.ALREADY_OWNED, "You already own this product.");
        }

        long price = product.getPricePaise();

        // Layer 2 — the buyer already has an open order for this product (other tab, page
        // reload with a new key). Reuse it instead of creating a second chargeable order.
        for (Order open : orderRepository.findOpenOrders(buyerId, productId)) {
            if (open.getTotalAmountPaise() == price) {
                log.info("initiateOrder reusing open order {} for buyer {}", open.getId(), buyerId);
                return toPayload(open);
            }
            // The creator changed the price since that order was created. Close it so it can
            // never be paid at the stale price, then fall through to create a fresh one.
            supersede(open);
        }

        Order order = createOrder(buyer, product, price, idempotencyKey);

        if (price == 0) {
            // D10 — free product: nothing to charge, so complete and fulfil right now.
            order.transitionTo(OrderStatus.COMPLETED);
            fulfillmentService.fulfil(order);
            log.info("Free order {} completed for buyer {}", order.getId(), buyerId);
            return toPayload(order);
        }

        // Ask the gateway for its order id. Our deterministic "receipt" is our key toward the
        // gateway — the second hop of idempotency (client → us → gateway).
        String receipt = "sl_order_" + order.getId();
        String gatewayOrderId = paymentGateway.createOrder(price, commerceProperties.currency(), receipt);
        order.setGatewayOrderId(gatewayOrderId);

        Payment payment = new Payment();
        payment.setOrder(order);
        payment.setIdempotencyKey(receipt);
        payment.setProviderName(paymentGateway.name());
        payment.setAmountPaise(price);
        paymentRepository.save(payment);
        paymentAuditService.recordCreated(payment, PaymentAuditService.SOURCE_CHECKOUT);

        log.info("Order {} created: buyer={}, product={}, amount={} paise, gatewayOrder={}",
                order.getId(), buyerId, productId, price, gatewayOrderId);
        return toPayload(order);
    }

    /** The checkout page's view of one order — scoped to its buyer (BOLA → NOT_FOUND). */
    @Transactional(readOnly = true)
    public OrderDto getOrderForBuyer(Long orderId, Long buyerId) {
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new ResourceNotFoundException("Order", orderId));
        return toOrderDto(order);
    }

    /** PAY-10 — lifetime earnings for the calling creator, from snapshotted splits (D7). */
    @Transactional(readOnly = true)
    public CreatorEarningsDto creatorEarnings(Long creatorId) {
        return orderItemRepository.sumEarningsForCreator(creatorId);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private Order createOrder(User buyer, Product product, long price, String idempotencyKey) {
        long fee = commerceProperties.platformFeeFor(price);

        Order order = new Order();
        order.setBuyer(buyer);
        order.setTotalAmountPaise(price);
        order.setIdempotencyKey(idempotencyKey);

        OrderItem item = new OrderItem();
        item.setOrder(order);
        item.setProduct(product);
        item.setPricePaise(price);                  // price snapshot — the product's price may change later
        item.setPlatformFeePaise(fee);              // D7 — commission snapshot
        item.setCreatorEarningsPaise(price - fee);  // derived by subtraction: always sums exactly
        order.getItems().add(item);

        return orderRepository.save(order);         // cascades the item; IDENTITY assigns the id now
    }

    private void supersede(Order stale) {
        stale.transitionTo(OrderStatus.FAILED);
        paymentRepository.findByOrderId(stale.getId()).ifPresent(p -> {
            p.setFailureReason("Superseded: the product's price changed.");
            paymentAuditService.transition(p, PaymentStatus.FAILED, PaymentAuditService.SOURCE_SYSTEM, null, null);
        });
        log.info("Superseded stale order {} (price changed)", stale.getId());
    }

    private InitiateOrderPayload toPayload(Order order) {
        boolean needsCheckout = order.getGatewayOrderId() != null && order.getStatus() == OrderStatus.PENDING;
        return new InitiateOrderPayload(
                toOrderDto(order),
                order.getGatewayOrderId(),
                needsCheckout ? paymentGateway.keyId() : null,
                commerceProperties.currency());
    }

    private OrderDto toOrderDto(Order order) {
        String failureReason = paymentRepository.findByOrderId(order.getId())
                .map(Payment::getFailureReason)
                .orElse(null);
        return CommerceMapper.toOrderDto(order, failureReason);
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "idempotencyKey is required (1–" + MAX_IDEMPOTENCY_KEY_LENGTH + " characters).");
        }
    }
}
