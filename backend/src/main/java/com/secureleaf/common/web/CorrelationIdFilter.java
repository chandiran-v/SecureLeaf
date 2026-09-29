package com.secureleaf.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Phase 9, D1 — the first filter every request passes through. Reads {@code X-Correlation-Id}
 * from the caller (so a request that already started upstream, e.g. at a load balancer or the
 * frontend, keeps the same id end-to-end) or mints a fresh UUID, puts it in SLF4J's MDC so every
 * log line written while handling this request carries it automatically, and echoes it back on
 * the response so the caller can quote it in a support request.
 *
 * WHY MDC AND NOT A METHOD PARAMETER
 * Passing a correlation id explicitly through every service/repository call in the codebase would
 * touch dozens of signatures for a field almost none of them actually use — it's purely an
 * observability concern, not business logic. MDC is a {@link ThreadLocal}-backed map the logging
 * framework reads implicitly, so every {@code log.info(...)} call anywhere on this thread picks it
 * up for free. The one place that breaks down is a request handed off to a different thread
 * (an {@code @Async} method) — see {@link com.secureleaf.common.config.MdcTaskDecorator}.
 *
 * Registered via {@code SecurityConfig.addFilterBefore(..., JwtAuthenticationFilter.class)} so it
 * runs before authentication — a request that gets rejected with 401 still gets a correlation id.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = firstNonBlank(request.getHeader(HEADER), UUID.randomUUID().toString());
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // Pool threads (Tomcat's request-handling threads) are reused for the next request —
            // without this, the next request on this thread would inherit a stale correlation id
            // until it's overwritten, which is fine 99% of the time but wrong the 1% a bug elsewhere
            // reads MDC before this filter runs again (e.g. a filter registered ahead of us by a
            // future change).
            MDC.remove(MDC_KEY);
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
