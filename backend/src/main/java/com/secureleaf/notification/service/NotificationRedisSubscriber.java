package com.secureleaf.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * The Redis half of Phase 6's fan-out (D7): every backend instance runs one of these, subscribed
 * (via {@link SseConfig}) to the pattern {@code notifications:user:*} that
 * {@link RedisNotificationPublisher} already publishes to.
 *
 * WHY RE-READ THE ROW INSTEAD OF FORWARDING THE REDIS PAYLOAD VERBATIM
 * {@link RedisNotificationPublisher} publishes a {@link NotificationCreatedEvent} — enough for
 * the email dispatcher, but it also carries {@code recipientEmail}, which has no business going
 * out over a stream the browser can inspect, and it's missing {@code createdAt}/{@code isRead}
 * that the frontend's {@code NotificationDto} shape needs. Re-fetching by id turns "something
 * changed, id 42" into the exact same {@code NotificationDto} the {@code myNotifications} query
 * would return — one mapping, one source of truth, no risk of the two ever drifting apart.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationRedisSubscriber implements MessageListener {

    static final String EVENT_NAME = "notification";

    private final ObjectMapper objectMapper;
    private final NotificationService notificationService;
    private final SseEmitterRegistry emitterRegistry;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        NotificationCreatedEvent event;
        try {
            event = objectMapper.readValue(message.getBody(), NotificationCreatedEvent.class);
        } catch (IOException e) {
            log.warn("Failed to parse a notification Pub/Sub message: {}", e.getMessage());
            return;
        }

        notificationService.findDtoById(event.notificationId()).ifPresent(dto -> {
            try {
                emitterRegistry.sendToUser(event.recipientId(), EVENT_NAME, objectMapper.writeValueAsString(dto));
            } catch (IOException e) {
                log.warn("Failed to serialize notification {} for SSE: {}", dto.id(), e.getMessage());
            }
        });
    }
}
