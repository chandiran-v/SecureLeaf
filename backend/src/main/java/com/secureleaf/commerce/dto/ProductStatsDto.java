package com.secureleaf.commerce.dto;

/**
 * One product's sales aggregate (Phase 6, D2) — {@code Product.salesCount}/{@code netEarningsPaise}.
 * Summed from the {@code creator_earnings_paise} snapshot on each {@code order_items} row (Phase 4,
 * D7), never recomputed from today's price or commission rate, so a later price/fee change can
 * never rewrite a past sale's numbers.
 */
public record ProductStatsDto(Long productId, long salesCount, long netEarningsPaise) {

    public static ProductStatsDto empty(Long productId) {
        return new ProductStatsDto(productId, 0L, 0L);
    }
}
