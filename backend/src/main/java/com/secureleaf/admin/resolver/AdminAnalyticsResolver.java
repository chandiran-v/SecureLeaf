package com.secureleaf.admin.resolver;

import com.secureleaf.admin.dto.AdminActionPageDto;
import com.secureleaf.admin.dto.PlatformStatsDto;
import com.secureleaf.admin.service.AdminAnalyticsService;
import com.secureleaf.admin.service.AdminAuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/** D2/D6/D7 — see {@link AdminUserResolver}'s javadoc for the authorisation split this mirrors. */
@Controller
@RequiredArgsConstructor
public class AdminAnalyticsResolver {

    private final AdminAnalyticsService adminAnalyticsService;
    private final AdminAuditService adminAuditService;

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN')")
    public PlatformStatsDto platformStats(@Argument Integer days) {
        return adminAnalyticsService.platformStats(days);
    }

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminActionPageDto adminActions(@Argument int page, @Argument int size) {
        return adminAuditService.adminActions(page, size);
    }
}
