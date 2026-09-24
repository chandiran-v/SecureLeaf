package com.secureleaf.notification.service;

/**
 * Real-time fan-out of a new notification (PAY-08). Redis Pub/Sub in the app; an in-memory
 * recorder in tests (so the suite doesn't need a Redis container) — same seam as
 * StorageService / InMemoryStorageService.
 */
public interface NotificationPublisher {

    void publish(NotificationCreatedEvent event);
}
