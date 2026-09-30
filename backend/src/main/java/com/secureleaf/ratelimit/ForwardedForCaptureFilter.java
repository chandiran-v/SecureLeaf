package com.secureleaf.ratelimit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Learning-moment filter. In prod {@code server.forward-headers-strategy=framework} installs
 * Spring's {@code ForwardedHeaderFilter}, which consumes and then HIDES {@code X-Forwarded-For}
 * from everything downstream — so by the time {@link RateLimitFilter} runs the header would be
 * gone and every visitor behind Caddy would share Caddy's IP (one preview bucket for the whole
 * internet). This filter runs first and copies the raw header into a request attribute for
 * {@link ClientIpResolver}. It only *copies*; whether to trust it is still ClientIpResolver's decision (D4).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ForwardedForCaptureFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null) {
            request.setAttribute(ClientIpResolver.RAW_XFF_ATTRIBUTE, xff);
        }
        chain.doFilter(request, response);
    }
}
