package com.secureleaf.notification.resolver;

import com.secureleaf.auth.service.SecureLeafUserDetails;
import com.secureleaf.notification.dto.NotificationDto;
import com.secureleaf.notification.service.NotificationService;
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

    @QueryMapping
    @PreAuthorize("isAuthenticated()")
    public List<NotificationDto> myNotifications() {
        return notificationService.myNotifications(getCurrentUserId());
    }

    @MutationMapping
    @PreAuthorize("isAuthenticated()")
    public boolean markNotificationRead(@Argument Long id) {
        return notificationService.markRead(id, getCurrentUserId());
    }

    private Long getCurrentUserId() {
        SecureLeafUserDetails userDetails = (SecureLeafUserDetails) SecurityContextHolder
                .getContext().getAuthentication().getPrincipal();
        return userDetails.getUserId();
    }
}
