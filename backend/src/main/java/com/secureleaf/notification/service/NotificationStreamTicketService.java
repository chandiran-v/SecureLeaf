package com.secureleaf.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * One-time tickets that authenticate the SSE stream (Phase 6, D7).
 *
 * WHY A TICKET AND NOT THE JWT ITSELF
 * The browser's {@code EventSource} API cannot set an {@code Authorization} header — it can only
 * GET a URL. Putting the access token directly in the URL would work functionally, but query
 * strings end up in server access logs, browser history and Referer headers; a JWT leaked that
 * way is a stolen session for as long as it's valid (up to 15 minutes, {@code jwt.access-token-expiry-ms}).
 *
 * A ticket fixes that by being: short-lived (30s — long enough to open one connection, far too
 * short to be useful if it leaks), single-use (consumed with Redis {@code GETDEL} the moment the
 * stream endpoint reads it, so even the same 30s window only works once), and scoped to nothing
 * but "which user is this" — it grants no access beyond opening the notification stream.
 */
@Service
@RequiredArgsConstructor
public class NotificationStreamTicketService {

    static final String TICKET_KEY_PREFIX = "sse-ticket:";
    private static final Duration TICKET_TTL = Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    /** {@code notificationStreamTicket} query — mints a fresh ticket for the calling user. */
    public String issueTicket(Long userId) {
        String ticket = randomTicket();
        redisTemplate.opsForValue().set(TICKET_KEY_PREFIX + ticket, String.valueOf(userId), TICKET_TTL);
        return ticket;
    }

    /**
     * Consumes the ticket — {@code GETDEL} reads and deletes atomically, so two concurrent
     * requests presenting the same ticket can never both succeed (the second one always finds
     * it already gone).
     *
     * @return the ticket's user id, or {@code null} if it's missing, expired, or already used.
     */
    public Long consumeTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) return null;
        String userId = redisTemplate.opsForValue().getAndDelete(TICKET_KEY_PREFIX + ticket);
        return userId == null ? null : Long.valueOf(userId);
    }

    private String randomTicket() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
