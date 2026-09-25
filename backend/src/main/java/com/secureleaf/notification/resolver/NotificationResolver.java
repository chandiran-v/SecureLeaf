package com.secureleaf.notification.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.notification.dto.NotificationDto;
import com.secureleaf.notification.service.NotificationService;
import com.secureleaf.notification.service.NotificationStreamTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class NotificationResolver {

    private final NotificationService notificationService;
    private final NotificationStreamTicketService notificationStreamTicketService;

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public List<NotificationDto> myNotifications() {
        return notificationService.myNotifications(getCurrentUserId());
    }

    /** D7 — a fresh, single-use ticket the client exchanges for an SSE connection. */
    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public String notificationStreamTicket() {
        return notificationStreamTicketService.issueTicket(getCurrentUserId());
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public boolean markNotificationRead(@Argument Long id) {
        return notificationService.markRead(id, getCurrentUserId());
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public int markAllNotificationsRead() {
        return notificationService.markAllRead(getCurrentUserId());
    }

    private Long getCurrentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
