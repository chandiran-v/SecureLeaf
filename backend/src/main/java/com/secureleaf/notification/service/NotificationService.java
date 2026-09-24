package com.secureleaf.notification.service;

import com.secureleaf.auth.entity.User;
import com.secureleaf.commerce.entity.Order;
import com.secureleaf.common.exception.ResourceNotFoundException;
import com.secureleaf.marketplace.entity.Product;
import com.secureleaf.notification.dto.NotificationDto;
import com.secureleaf.notification.entity.Notification;
import com.secureleaf.notification.entity.NotificationType;
import com.secureleaf.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.secureleaf.common.util.Money.formatRupees;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * PAY-08 — one notification for the buyer, one for the creator.
     *
     * {@code Propagation.MANDATORY}: this must join the caller's purchase transaction, so the
     * rows commit or roll back together with the entitlement. Called without a transaction,
     * Spring throws immediately instead of quietly committing notifications for a purchase
     * that may never happen.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyPurchase(Order order, Product product) {
        User buyer = order.getBuyer();
        String price = formatRupees(order.getTotalAmountPaise());

        create(buyer, NotificationType.PURCHASE_SUCCESS, order, product,
                "Purchase confirmed: " + product.getTitle(),
                "You now own \"" + product.getTitle() + "\" (" + price + "). It's in your library.");

        create(product.getCreator(), NotificationType.SALE_RECEIVED, order, product,
                "New sale: " + product.getTitle(),
                buyer.getDisplayName() + " bought \"" + product.getTitle() + "\" for " + price + ".");
    }

    @Transactional(readOnly = true)
    public List<NotificationDto> myNotifications(Long userId) {
        return notificationRepository.findTop20ByRecipientIdOrderByCreatedAtDescIdDesc(userId).stream()
                .map(NotificationService::toDto)
                .toList();
    }

    /** Idempotent: marking an already-read notification read again is a no-op success. */
    @Transactional
    public boolean markRead(Long notificationId, Long userId) {
        Notification n = notificationRepository.findByIdAndRecipientId(notificationId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", notificationId));
        if (!n.getIsRead()) {
            n.setIsRead(true);
            n.setReadAt(Instant.now());
        }
        return true;
    }

    private void create(User recipient, NotificationType type, Order order, Product product,
                        String title, String body) {
        Notification n = new Notification();
        n.setRecipient(recipient);
        n.setType(type);
        n.setRelatedOrder(order);
        n.setRelatedProduct(product);
        n.setTitle(title);
        n.setBody(body);
        Notification saved = notificationRepository.save(n);

        // Held by Spring until this transaction commits — see NotificationDispatcher.
        eventPublisher.publishEvent(new NotificationCreatedEvent(
                saved.getId(), recipient.getId(), recipient.getEmail(), type.name(), title, body));
    }

    private static NotificationDto toDto(Notification n) {
        return new NotificationDto(
                n.getId(),
                n.getType().name(),
                n.getTitle(),
                n.getBody(),
                Boolean.TRUE.equals(n.getIsRead()),
                n.getCreatedAt() != null ? n.getCreatedAt().atOffset(ZoneOffset.UTC) : null);
    }
}
