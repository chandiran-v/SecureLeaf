package com.secureleaf.admin.dto;

import java.util.List;

/** D6 — the GraphQL {@code PlatformStats} type: all-time and last-{@code windowDays} figures
 *  side by side, so the dashboard renders both without a second round trip. */
public record PlatformStatsDto(
        int totalUsers,
        int totalCreators,
        int totalLiveProducts,
        int completedOrdersAllTime,
        long grossSalesPaiseAllTime,
        long platformFeePaiseAllTime,
        int windowDays,
        int completedOrdersWindow,
        long grossSalesPaiseWindow,
        long platformFeePaiseWindow,
        List<TopProductDto> topProducts
) {}
