package com.secureleaf.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds every open SSE connection ON THIS INSTANCE, keyed by user id (Phase 6, D7).
 *
 * FAN-OUT ACROSS MULTIPLE BACKEND INSTANCES
 * A browser's {@code EventSource} connects to exactly one backend instance (whichever one the
 * load balancer picked). If a creator's notification is created by a request that lands on
 * instance A, but the creator's open stream is held by instance B, instance A has no direct way
 * to reach instance B's in-memory map. {@link NotificationRedisSubscriber} is what closes that
 * gap: {@link RedisNotificationPublisher} publishes to Redis (which every instance subscribes
 * to), so EVERY instance's listener fires and calls {@link #sendToUser} — the ones with no
 * emitter for that user simply do nothing.
 *
 * AT-MOST-ONCE, BY DESIGN
 * Redis Pub/Sub delivers to whoever is subscribed *right now* and nothing else — no queue, no
 * replay. A notification published while a user has no open tab is simply never pushed. That's
 * fine here because Pub/Sub is only the "something changed, go look" signal: {@code notifications}
 * is the durable source of truth, and the bell's initial {@code myNotifications} query (plus its
 * polling fallback) is what a reconnecting client uses to catch up on anything it missed.
 */
@Component
@Slf4j
public class SseEmitterRegistry {

    private static final Duration EMITTER_TIMEOUT = Duration.ofMinutes(30);

    private final Map<Long, Set<SseEmitter>> emittersByUser = new ConcurrentHashMap<>();

    public SseEmitter register(Long userId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT.toMillis());
        Set<SseEmitter> emitters = emittersByUser.computeIfAbsent(userId, id -> ConcurrentHashMap.newKeySet());
        emitters.add(emitter);

        emitter.onCompletion(() -> remove(userId, emitter));
        emitter.onTimeout(() -> remove(userId, emitter));
        emitter.onError(e -> remove(userId, emitter));

        return emitter;
    }

    /** Called by {@link NotificationRedisSubscriber} for every message on this instance. */
    public void sendToUser(Long userId, String eventName, String jsonPayload) {
        Set<SseEmitter> emitters = emittersByUser.get(userId);
        if (emitters == null || emitters.isEmpty()) return;

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(jsonPayload, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                // The browser navigated away or the connection dropped between our liveness
                // check and this send — completing it here triggers onError's cleanup below.
                emitter.completeWithError(e);
            }
        }
    }

    /** D7 — every 25s, keeps proxies/load balancers from treating a quiet-but-healthy stream as dead. */
    @Scheduled(fixedRate = 25_000)
    public void sendHeartbeats() {
        emittersByUser.forEach((userId, emitters) -> {
            for (SseEmitter emitter : Set.copyOf(emitters)) {
                try {
                    emitter.send(SseEmitter.event().comment(""));
                } catch (IOException | IllegalStateException e) {
                    emitter.completeWithError(e);
                }
            }
        });
    }

    private void remove(Long userId, SseEmitter emitter) {
        emittersByUser.computeIfPresent(userId, (id, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
