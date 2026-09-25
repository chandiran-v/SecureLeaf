package com.secureleaf.notification;

import com.secureleaf.notification.service.NotificationRedisSubscriber;
import com.secureleaf.notification.service.RedisNotificationPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Wires {@link NotificationRedisSubscriber} to every channel {@link RedisNotificationPublisher}
 * publishes to (Phase 6, D7) — one listener container per backend instance, each independently
 * subscribed, which is what makes the fan-out work across more than one instance (see
 * {@link com.secureleaf.notification.service.SseEmitterRegistry}'s javadoc).
 */
@Configuration
@RequiredArgsConstructor
public class SseConfig {

    @Bean
    public RedisMessageListenerContainer notificationRedisListenerContainer(
            RedisConnectionFactory connectionFactory, NotificationRedisSubscriber subscriber) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new PatternTopic(RedisNotificationPublisher.CHANNEL_PREFIX + "*"));
        return container;
    }
}
