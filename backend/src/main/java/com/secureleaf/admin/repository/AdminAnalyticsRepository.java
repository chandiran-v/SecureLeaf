package com.secureleaf.admin.repository;

import com.secureleaf.admin.dto.TopProductDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * D6 — {@code platformStats}, plain aggregate SQL (per the spec) rather than pulling every
 * order/product into Java and summing there: Postgres already holds these rows and can compute
 * a COUNT/SUM/GROUP BY far more cheaply than round-tripping them all to the JVM first.
 */
@Repository
public class AdminAnalyticsRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public long countUsers() {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM users WHERE deleted_at IS NULL").getSingleResult()).longValue();
    }

    public long countCreators() {
        return ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(DISTINCT ur.user_id)
                FROM user_roles ur JOIN users u ON u.id = ur.user_id
                WHERE ur.role = 'CREATOR' AND u.deleted_at IS NULL
                """).getSingleResult()).longValue();
    }

    public long countLiveProducts() {
        return ((Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM products WHERE status = 'LIVE' AND deleted_at IS NULL")
                .getSingleResult()).longValue();
    }

    /** @param since {@code null} for all-time; otherwise only orders created on/after this instant. */
    public OrderAggregate ordersAggregate(Instant since) {
        String sql = """
                SELECT COUNT(DISTINCT o.id), COALESCE(SUM(oi.price_paise), 0), COALESCE(SUM(oi.platform_fee_paise), 0)
                FROM orders o JOIN order_items oi ON oi.order_id = o.id
                WHERE o.status = 'COMPLETED'
                """ + (since != null ? " AND o.created_at >= :since" : "");
        Query query = entityManager.createNativeQuery(sql);
        if (since != null) {
            query.setParameter("since", Timestamp.from(since));
        }
        Object[] row = (Object[]) query.getSingleResult();
        return new OrderAggregate(
                ((Number) row[0]).longValue(),
                ((Number) row[1]).longValue(),
                ((Number) row[2]).longValue());
    }

    @SuppressWarnings("unchecked")
    public List<TopProductDto> topProductsSince(Instant since, int limit) {
        Query query = entityManager.createNativeQuery("""
                SELECT p.id, p.title, COUNT(oi.id), COALESCE(SUM(oi.price_paise), 0)
                FROM order_items oi
                JOIN orders o ON o.id = oi.order_id
                JOIN products p ON p.id = oi.product_id
                WHERE o.status = 'COMPLETED' AND o.created_at >= :since
                GROUP BY p.id, p.title
                ORDER BY COUNT(oi.id) DESC, p.id ASC
                LIMIT :limit
                """);
        query.setParameter("since", Timestamp.from(since));
        query.setParameter("limit", limit);
        List<Object[]> rows = query.getResultList();
        return rows.stream()
                .map(r -> new TopProductDto(
                        ((Number) r[0]).longValue(),
                        (String) r[1],
                        ((Number) r[2]).intValue(),
                        ((Number) r[3]).longValue()))
                .toList();
    }

    public record OrderAggregate(long completedOrders, long grossSalesPaise, long platformFeePaise) {}
}
