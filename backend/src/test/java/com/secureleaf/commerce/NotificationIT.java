package com.secureleaf.commerce;

import com.secureleaf.commerce.gateway.MockWebhookSender;
import com.secureleaf.notification.service.NotificationCreatedEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * PAY-08 — purchase notifications (D9): rows written in the purchase transaction; the Redis
 * publish happens after commit, on a background thread, and never for a rolled-back purchase.
 */
class NotificationIT extends AbstractCommerceIT {

    private static final String MY_NOTIFICATIONS = "query { myNotifications { id type title isRead } }";

    @Test
    void purchase_notifiesBuyerAndCreator_andPublishesAfterCommit() throws Exception {
        Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());
        postWebhook(MockWebhookSender.EVENT_CAPTURED, ids.get("gatewayOrderId"), "pay_1", PRICE_PAISE, "evt_1");

        asBuyer.document(MY_NOTIFICATIONS).execute()
                .path("myNotifications[*].type").entityList(String.class).containsExactly("PURCHASE_SUCCESS");
        asCreator.document(MY_NOTIFICATIONS).execute()
                .path("myNotifications[*].type").entityList(String.class).containsExactly("SALE_RECEIVED");

        // @Async — the publish lands shortly after the webhook's transaction commits.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(notificationPublisher.published())
                        .extracting(NotificationCreatedEvent::recipientId)
                        .containsExactlyInAnyOrder(buyer.getId(), creator.getId()));
    }

    @Test
    void rolledBackPurchase_publishesNothing() throws Exception {
        Map<String, String> ids = initiate(asBuyer, paidProduct, newKey());

        // Amount mismatch → the transaction throws and rolls back after nothing was granted.
        postWebhook(MockWebhookSender.EVENT_CAPTURED, ids.get("gatewayOrderId"), "pay_1", 1, "evt_1");

        Thread.sleep(500);   // give a (wrongly) scheduled async publish time to show up
        assertThat(notificationPublisher.published()).isEmpty();
        assertThat(count("SELECT count(*) FROM notifications")).isZero();
    }

    @Test
    void markNotificationRead_isScopedToTheRecipient() {
        asBuyer.document(INITIATE).variable("productId", freeProduct.getId()).variable("key", newKey()).execute();
        String notificationId = asBuyer.document(MY_NOTIFICATIONS).execute()
                .path("myNotifications[0].id").entity(String.class).get();

        // BOLA — another user can't touch it; it looks like it doesn't exist.
        asOtherBuyer.document("mutation($id: ID!) { markNotificationRead(id: $id) }").variable("id", notificationId)
                .execute().errors().expect(e -> "NOT_FOUND".equals(e.getExtensions().get("code"))).verify();

        asBuyer.document("mutation($id: ID!) { markNotificationRead(id: $id) }").variable("id", notificationId)
                .execute().path("markNotificationRead").entity(Boolean.class).isEqualTo(true);
        asBuyer.document(MY_NOTIFICATIONS).execute()
                .path("myNotifications[0].isRead").entity(Boolean.class).isEqualTo(true);
    }

    @Test
    void markAllNotificationsRead_flipsEveryUnreadRow_andReturnsHowMany() {
        asBuyer.document(INITIATE).variable("productId", freeProduct.getId()).variable("key", newKey()).execute();
        asBuyer.document(INITIATE).variable("productId", paidProduct.getId()).variable("key", newKey()).execute();

        asBuyer.document("mutation { markAllNotificationsRead }").execute()
                .path("markAllNotificationsRead").entity(Integer.class).isEqualTo(1);

        asBuyer.document(MY_NOTIFICATIONS).execute()
                .path("myNotifications[*].isRead").entityList(Boolean.class).get()
                .forEach(isRead -> assertThat(isRead).isTrue());

        // Idempotent: nothing left unread, so a second call flips zero rows.
        asBuyer.document("mutation { markAllNotificationsRead }").execute()
                .path("markAllNotificationsRead").entity(Integer.class).isEqualTo(0);
    }
}
