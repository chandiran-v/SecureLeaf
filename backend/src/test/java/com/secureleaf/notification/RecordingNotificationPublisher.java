package com.secureleaf.notification;

import com.secureleaf.notification.service.NotificationCreatedEvent;
import com.secureleaf.notification.service.NotificationPublisher;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test replacement for RedisNotificationPublisher — same @Primary trick as
 * InMemoryStorageService, so the suite needs no Redis container. Records every published
 * event so tests can assert the after-commit side effect actually happened (and happened
 * only for committed purchases).
 *
 * {@code @Profile("!sse-real-redis")} — the one exception is {@code NotificationSseIT} (D9),
 * which activates the {@code sse-real-redis} profile so this bean drops out entirely and the
 * real {@link com.secureleaf.notification.service.RedisNotificationPublisher} (the only
 * remaining {@code NotificationPublisher} bean, needing no {@code @Primary} of its own) carries
 * the message through Redis Pub/Sub to the SSE listener, end to end.
 */
@Service
@Primary
@Profile("!sse-real-redis")
public class RecordingNotificationPublisher implements NotificationPublisher {

    private final List<NotificationCreatedEvent> published = new CopyOnWriteArrayList<>();

    @Override
    public void publish(NotificationCreatedEvent event) {
        published.add(event);
    }

    public List<NotificationCreatedEvent> published() {
        return List.copyOf(published);
    }

    public void clear() {
        published.clear();
    }
}
