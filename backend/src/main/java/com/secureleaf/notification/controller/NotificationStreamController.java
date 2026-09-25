package com.secureleaf.notification.controller;

import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.notification.service.NotificationStreamTicketService;
import com.secureleaf.notification.service.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/notifications/stream?ticket=...} — the SSE endpoint (Phase 6, D7).
 *
 * REST, not GraphQL: GraphQL responses are single JSON documents, not an open, server-push
 * stream — {@code text/event-stream} is an HTTP-level content type {@code SseEmitter} handles
 * natively, the same "REST for anything that isn't a normal request/response GraphQL exchange"
 * reasoning as file upload/download (CLAUDE.md) and the DRM tile endpoint (Phase 5).
 *
 * Public at the HTTP level (SecurityConfig permits this exact GET) because {@code EventSource}
 * cannot send an {@code Authorization} header — authentication here is entirely the ticket:
 * {@link NotificationStreamTicketService#consumeTicket} either returns a user id (the ticket was
 * genuine and unused) or {@code null} (missing/expired/replayed), and a {@code null} is a 401,
 * indistinguishable from "no ticket at all" — same reasoning as D6's uniform failures elsewhere.
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationStreamController {

    private final NotificationStreamTicketService ticketService;
    private final SseEmitterRegistry emitterRegistry;

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String ticket) {
        Long userId = ticketService.consumeTicket(ticket);
        if (userId == null) {
            throw new BusinessException(ErrorCode.SSE_TICKET_INVALID, "Missing, unknown, or already-used ticket.");
        }
        return emitterRegistry.register(userId);
    }
}
