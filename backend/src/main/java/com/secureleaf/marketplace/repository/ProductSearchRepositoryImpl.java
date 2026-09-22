package com.secureleaf.marketplace.repository;

import com.secureleaf.marketplace.dto.ProductFilterInput;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Native-SQL implementation of {@link ProductSearchRepository}.
 *
 * WHY NATIVE SQL INSTEAD OF CRITERIA/SPECIFICATIONS (D1)
 * The full-text search predicate must be written verbatim:
 *
 *   to_tsvector('english', title || ' ' || description) @@ plainto_tsquery('english', :q)
 *
 * {@code idx_products_fts} (V1__init_schema.sql) is a GIN *expression* index — Postgres
 * only uses it when the query contains this exact expression. The Criteria API can emit
 * it via {@code cb.function(...)}, but the builder chain is unreadable and may
 * parenthesize or cast the expression differently, silently falling back to a sequential
 * scan with no error. For a codebase whose purpose is teaching, hiding the SQL behind a
 * builder also destroys the lesson. QueryDSL was rejected too — pulling in an
 * annotation-processor codegen pipeline for one query surface isn't worth it.
 *
 * WHY TWO-STEP ID PAGING INSTEAD OF @EntityGraph + Pageable (D2)
 * Fetching Product with its `tags` collection joined AND a Pageable in the same query
 * makes Hibernate emit HHH000104 ("firstResult/maxResults specified with collection
 * fetch; applying in memory") — it silently loads the ENTIRE matching table into the
 * JVM and paginates there. For a marketplace that can have thousands of LIVE products,
 * that is a correctness-shaped performance bug, not just a slow query.
 *
 * This class only does steps 1 and 2 — the id page and its matching COUNT(*), both with
 * an identical WHERE clause and no collection join, so Postgres paginates using the
 * index instead of Hibernate paginating in memory. Step 3 (the @EntityGraph fetch of the
 * full entities) lives on {@link ProductRepository#findAllByIdIn} and is invoked by
 * {@link com.secureleaf.marketplace.service.ProductSearchService}, which also re-orders
 * the result back to this class's id order — the step-3 JOIN does not preserve it.
 */
@Repository
public class ProductSearchRepositoryImpl implements ProductSearchRepository {

    @PersistenceContext
    private EntityManager entityManager;

    private static final String FTS_PREDICATE =
            "to_tsvector('english', p.title || ' ' || p.description) @@ plainto_tsquery('english', :q)";

    @Override
    public ProductIdPage searchLiveProductIds(ProductFilterInput filter, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE p.status = 'LIVE' AND p.deleted_at IS NULL ");
        Map<String, Object> params = new LinkedHashMap<>();

        boolean hasSearch = filter != null && filter.searchQuery() != null && !filter.searchQuery().isBlank();
        if (hasSearch) {
            where.append(" AND ").append(FTS_PREDICATE).append(' ');
            params.put("q", filter.searchQuery().trim());
        }
        if (filter != null && filter.categorySlug() != null && !filter.categorySlug().isBlank()) {
            where.append(" AND p.category_id = (SELECT id FROM categories WHERE slug = :categorySlug) ");
            params.put("categorySlug", filter.categorySlug());
        }
        if (filter != null && filter.minPricePaise() != null) {
            where.append(" AND p.price_paise >= :minPrice ");
            params.put("minPrice", filter.minPricePaise());
        }
        if (filter != null && filter.maxPricePaise() != null) {
            where.append(" AND p.price_paise <= :maxPrice ");
            params.put("maxPrice", filter.maxPricePaise());
        }
        if (filter != null && Boolean.TRUE.equals(filter.isFree())) {
            where.append(" AND p.price_paise = 0 ");
        }

        String orderBy = resolveOrderBy(filter, hasSearch);

        List<Long> ids = fetchIds(where.toString(), params, orderBy, pageable);
        long total = fetchCount(where.toString(), params);
        return new ProductIdPage(ids, total);
    }

    /**
     * sortBy is a WHITELIST SWITCH, never interpolated — this is what makes the
     * dynamic ORDER BY safe from SQL injection despite the query being hand-built.
     */
    private String resolveOrderBy(ProductFilterInput filter, boolean hasSearch) {
        String sortBy = filter != null ? filter.sortBy() : null;
        if (sortBy == null || sortBy.isBlank()) {
            return hasSearch
                    ? "ts_rank(to_tsvector('english', p.title || ' ' || p.description), plainto_tsquery('english', :q)) DESC, p.created_at DESC"
                    : "p.created_at DESC";
        }
        return switch (sortBy) {
            case "newest" -> "p.created_at DESC";
            case "popular" -> "p.total_sales DESC";
            case "rating" -> "p.average_rating DESC NULLS LAST";
            case "price_asc" -> "p.price_paise ASC";
            case "price_desc" -> "p.price_paise DESC";
            default -> "p.created_at DESC";
        };
    }

    @SuppressWarnings("unchecked")
    private List<Long> fetchIds(String where, Map<String, Object> params, String orderBy, Pageable pageable) {
        String sql = "SELECT p.id FROM products p" + where + " ORDER BY " + orderBy
                + " LIMIT :limit OFFSET :offset";
        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        query.setParameter("limit", pageable.getPageSize());
        query.setParameter("offset", pageable.getOffset());
        List<Object> raw = query.getResultList();
        return raw.stream().map(o -> ((Number) o).longValue()).toList();
    }

    private long fetchCount(String where, Map<String, Object> params) {
        String sql = "SELECT COUNT(*) FROM products p" + where;
        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }
}
