package com.secureleaf.admin.resolver;

import com.secureleaf.ratelimit.ScraperSignalService;
import com.secureleaf.ratelimit.SuspectedScraperDto;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;

/** Phase 11, D6 — the admin's view of possible scrapers. Read-only; admins decide what to do. */
@Controller
@RequiredArgsConstructor
public class SuspectedScraperResolver {

    private final ScraperSignalService scraperSignalService;

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN')")
    public List<SuspectedScraperDto> suspectedScrapers(@Argument int windowMinutes) {
        return scraperSignalService.suspectedScrapers(windowMinutes);
    }
}
