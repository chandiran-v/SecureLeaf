package com.secureleaf.viewer.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.viewer.dto.SignedPageUrlDto;
import com.secureleaf.viewer.dto.ViewerHeartbeatDto;
import com.secureleaf.viewer.dto.ViewerSessionDto;
import com.secureleaf.viewer.service.ViewerSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

/**
 * GraphQL entry points for the D1 session lifecycle. Any authenticated user can open a viewer
 * session — object-level access control (does this buyer actually own this product?) lives in
 * {@link ViewerSessionService}, which throws {@code ErrorCode.NOT_ENTITLED} for VIEW-01.
 */
@Controller
@RequiredArgsConstructor
public class ViewerResolver {

    private final ViewerSessionService viewerSessionService;
    private final HttpServletRequest request;

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public ViewerSessionDto startViewerSession(@Argument Long productId, @Argument String deviceFingerprint) {
        return viewerSessionService.startViewerSession(getCurrentUserId(), productId, deviceFingerprint, request.getRemoteAddr());
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public ViewerHeartbeatDto viewerHeartbeat(@Argument String sessionToken) {
        return viewerSessionService.heartbeat(sessionToken);
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public boolean endViewerSession(@Argument String sessionToken) {
        return viewerSessionService.endViewerSession(sessionToken);
    }

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public SignedPageUrlDto viewerPageUrl(@Argument String sessionToken, @Argument int pageNumber) {
        return viewerSessionService.signPageUrl(getCurrentUserId(), sessionToken, pageNumber);
    }

    private Long getCurrentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
