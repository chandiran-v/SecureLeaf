package com.secureleaf.auth.security;

import com.secureleaf.auth.entity.AccountStatus;
import com.secureleaf.auth.entity.User;
import com.secureleaf.auth.repository.UserRepository;
import com.secureleaf.auth.service.JwtService;
import com.secureleaf.auth.service.SecureLeafUserDetails;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Extracts the JWT from the {@code Authorization: Bearer <token>} header,
 * validates it, loads the user, and populates the Spring Security context.
 *
 * <ul>
 *   <li>No token: the chain continues unauthenticated; downstream rules reject protected
 *       operations.</li>
 *   <li>A token that is <b>expired</b> or <b>invalid</b> (bad signature, malformed): the request
 *       stops here with {@code 401} and a JSON body {@code {"code": "TOKEN_EXPIRED" | "INVALID_TOKEN",
 *       "message": ...}} plus {@code WWW-Authenticate: Bearer error="invalid_token"} (RFC 6750).
 *       The client refreshes on TOKEN_EXPIRED, or tells the user their session ended.
 *       This used to be handed to the MVC exception resolver, which had no handler for JWT
 *       exceptions and so wrote nothing: an empty HTTP 200 that clients couldn't parse.</li>
 *   <li>A valid token for a user who is suspended, deleted or not ACTIVE: the chain continues
 *       unauthenticated (Phase 8, D4), so protected operations fail as for an anonymous caller.</li>
 * </ul>
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final SuspendedUsersService suspendedUsersService;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository,
                                   SuspendedUsersService suspendedUsersService) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
        this.suspendedUsersService = suspendedUsersService;
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

        // Only token parsing is guarded here. The rest of the chain runs outside the try, so an
        // exception thrown by a controller further down is never mistaken for a bad token.
        Long userId;
        try {
            userId = jwtService.extractUserId(token);
        } catch (ExpiredJwtException e) {
            writeUnauthorized(response, "TOKEN_EXPIRED", "Your session has expired. Please log in again.");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            writeUnauthorized(response, "INVALID_TOKEN", "Your session is no longer valid. Please log in again.");
            return;
        }

        User user = userRepository.findWithRolesById(userId).orElse(null);
        boolean usable = user != null
                && user.getAccountStatus() == AccountStatus.ACTIVE
                && user.getDeletedAt() == null
                && !suspendedUsersService.isSuspended(userId);

        if (usable) {
            SecureLeafUserDetails userDetails = new SecureLeafUserDetails(user);
            UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
            authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(request, response);
    }

    /** RFC 6750 §3.1: an invalid or expired bearer token is a 401 with error="invalid_token". */
    private static void writeUnauthorized(HttpServletResponse response, String code, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\", error_description=\"" + code + "\"");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        // code and message are compile-time constants from this class: no escaping needed.
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
