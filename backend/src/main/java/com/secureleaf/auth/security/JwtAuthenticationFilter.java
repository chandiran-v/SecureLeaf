package com.secureleaf.auth.security;

import com.secureleaf.auth.entity.AccountStatus;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.JwtService;
import com.secureleaf.auth.service.SecureLeafUserDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Extracts the JWT from the {@code Authorization: Bearer <token>} header,
 * validates it, loads the user, and populates the Spring Security context.
 *
 * <p>If no token is present or validation fails, the filter chain continues
 * without authentication — downstream security rules will then reject
 * protected operations.</p>
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SuspendedUsersService suspendedUsersService;
    private final HandlerExceptionResolver handlerExceptionResolver;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
                                   SuspendedUsersService suspendedUsersService,
                                   @Qualifier("handlerExceptionResolver") HandlerExceptionResolver handlerExceptionResolver) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.suspendedUsersService = suspendedUsersService;
        this.handlerExceptionResolver = handlerExceptionResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {
            Long userId = jwtService.extractUserId(token);
            User user = userRepository.findWithRolesById(userId).orElse(null);

            if (user == null || user.getAccountStatus() != AccountStatus.ACTIVE || user.getDeletedAt() != null) {
                filterChain.doFilter(request, response);
                return;
            }

            // Phase 8, D4 — catches a token minted before a suspension that revoked it, without
            // waiting out the access token's remaining lifetime. Fails open (see
            // SuspendedUsersService's javadoc) if Redis itself is the thing that's down.
            if (suspendedUsersService.isSuspended(userId)) {
                filterChain.doFilter(request, response);
                return;
            }

            SecureLeafUserDetails userDetails = new SecureLeafUserDetails(user);
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);

            filterChain.doFilter(request, response);
        } catch (Exception e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            handlerExceptionResolver.resolveException(request, response, null, e);
        }
    }
}
