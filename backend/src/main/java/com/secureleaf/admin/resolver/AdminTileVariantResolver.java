package com.secureleaf.admin.resolver;

import com.secureleaf.content.tiles.VariantBackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/** Phase 16, D4 — admin-only trigger for the tile-variant backfill. */
@Controller
@RequiredArgsConstructor
public class AdminTileVariantResolver {

    private final VariantBackfillService backfillService;

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public boolean generateMissingVariants() {
        return backfillService.start();
    }
}
