package com.secureleaf.ratelimit;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Phase 11, D3 — the HTTP-level limiter. Registered in SecurityConfig right AFTER
 * {@code JwtAuthenticationFilter} so the buyer's id is already known:
 * <ul>
 *   <li>{@code GET /api/viewer/tiles/**} — bucket {@code tile:{userId}}. No authenticated user
 *       means we let the request through untouched: Spring Security answers 401 next.</li>
 *   <li>{@code GET /api/products/{id}/preview/{n}} — bucket {@code preview:{clientIp}} (public endpoint).</li>
 * </ul>
 * The check happens here, before the controller, so a rejected request never reaches storage or
 * the watermark renderer — the whole point is to make rejection cheap.
 *
 * Deviation from the spec's wording ("preview before authentication"): one filter after the JWT
 * filter serves both. The preview path is permitAll and IP-keyed, so nothing it does depends on
 * authentication; splitting it into a second filter would only add a class.
 */
@Component
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String TILE_PREFIX = "/api/viewer/tiles/";
    private static final Pattern PREVIEW_PATH = Pattern.compile("^/api/products/[^/]+/preview/[^/]+$");

    private final RateLimiter rateLimiter;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !(path.startsWith(TILE_PREFIX) || PREVIEW_PATH.matcher(path).matches());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RateLimitBucket bucket;
        String key;
        if (request.getRequestURI().startsWith(TILE_PREFIX)) {
            Long userId = currentUserId();
            if (userId == null) {
                chain.doFilter(request, response);
                return;
            }
            bucket = RateLimitBucket.TILE;
            key = String.valueOf(userId);
        } else {
            bucket = RateLimitBucket.PREVIEW;
            key = clientIpResolver.resolve(request);
        }

        RateLimitDecision decision = rateLimiter.tryConsume(bucket, key);
        if (decision.remainingTokens() >= 0) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remainingTokens()));
        }
        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests. Please slow down.\","
                + "\"retryAfterSeconds\":" + decision.retryAfterSeconds() + "}");
    }

    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof SecureLeafUserDetails details) {
            return details.getUserId();
        }
        return null;
    }
}
