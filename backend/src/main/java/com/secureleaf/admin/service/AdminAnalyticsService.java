package com.secureleaf.admin.service;

import com.secureleaf.admin.dto.PlatformStatsDto;
import com.secureleaf.admin.repository.AdminAnalyticsRepository;
import com.secureleaf.admin.repository.AdminAnalyticsRepository.OrderAggregate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** D6 — {@code platformStats}. */
@Service
@RequiredArgsConstructor
public class AdminAnalyticsService {

    private static final int TOP_PRODUCTS_LIMIT = 5;

    private final AdminAnalyticsRepository adminAnalyticsRepository;

    @Transactional(readOnly = true)
    public PlatformStatsDto platformStats(Integer daysArg) {
        int days = daysArg == null ? 30 : Math.max(daysArg, 1);
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);

        long totalUsers = adminAnalyticsRepository.countUsers();
        long totalCreators = adminAnalyticsRepository.countCreators();
        long totalLiveProducts = adminAnalyticsRepository.countLiveProducts();

        OrderAggregate allTime = adminAnalyticsRepository.ordersAggregate(null);
        OrderAggregate window = adminAnalyticsRepository.ordersAggregate(since);

        return new PlatformStatsDto(
                Math.toIntExact(totalUsers),
                Math.toIntExact(totalCreators),
                Math.toIntExact(totalLiveProducts),
                Math.toIntExact(allTime.completedOrders()),
                allTime.grossSalesPaise(),
                allTime.platformFeePaise(),
                days,
                Math.toIntExact(window.completedOrders()),
                window.grossSalesPaise(),
                window.platformFeePaise(),
                adminAnalyticsRepository.topProductsSince(since, TOP_PRODUCTS_LIMIT)
        );
    }
}
