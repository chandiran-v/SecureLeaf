package com.secureleaf.notification.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes each new notification to the Redis channel {@code notifications:user:{id}}.
 *
 * PUB/SUB IN ONE PARAGRAPH
 * Publishers send a message to a named channel; every client *currently subscribed* to that
 * channel receives it; nobody else ever will. Redis stores nothing — it's a radio broadcast,
 * not a mailbox. That's why the notifications table is the source of truth and Pub/Sub is
 * only the "ping, something new arrived" signal.
 *
 * Phase 6 adds {@link NotificationRedisSubscriber}, which subscribes to this exact channel
 * pattern and forwards each message to whichever browser tab(s) hold an open SSE connection for
 * that user (see {@link SseEmitterRegistry}).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RedisNotificationPublisher implements NotificationPublisher {

    /** Public: {@link NotificationRedisSubscriber} subscribes to this exact pattern + "*". */
    public static final String CHANNEL_PREFIX = "notifications:user:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public void publish(NotificationCreatedEvent event) {
        try {
            redisTemplate.convertAndSend(CHANNEL_PREFIX + event.recipientId(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
