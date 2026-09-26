package com.secureleaf.admin.repository;

import com.secureleaf.admin.dto.AdminProductFilterDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D5 — {@code adminProducts}' search/filter/paginate. Same two-step id-paging shape as
 * {@code ProductSearchRepositoryImpl} and {@link AdminUserQueryRepository}, and for the same
 * reason: {@link com.secureleaf.admin.service.AdminProductService} fetches the full entities
 * (with their {@code tags} collection) via {@code ProductRepository.findAllByIdIn} afterwards.
 *
 * Unlike the public marketplace search, this deliberately has NO {@code status = 'LIVE'} filter
 * — an admin must be able to find and moderate a DRAFT, PROCESSING, UNPUBLISHED or FAILED
 * product too, not just what buyers can already see.
 */
@Repository
public class AdminProductQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public IdPage searchProductIds(AdminProductFilterDto filter, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE p.deleted_at IS NULL ");
        Map<String, Object> params = new LinkedHashMap<>();

        if (filter != null && filter.search() != null && !filter.search().isBlank()) {
            where.append(" AND LOWER(p.title) LIKE :search ");
            params.put("search", "%" + filter.search().trim().toLowerCase() + "%");
        }
        if (filter != null && filter.status() != null) {
            where.append(" AND p.status = :status ");
            params.put("status", filter.status().name());
        }
        if (filter != null && filter.creatorId() != null) {
            where.append(" AND p.creator_id = :creatorId ");
            params.put("creatorId", filter.creatorId());
        }

        List<Long> ids = fetchIds(where.toString(), params, pageable);
        long total = fetchCount(where.toString(), params);
        return new IdPage(ids, total);
    }

    @SuppressWarnings("unchecked")
    private List<Long> fetchIds(String where, Map<String, Object> params, Pageable pageable) {
        String sql = "SELECT p.id FROM products p" + where
                + " ORDER BY p.created_at DESC, p.id DESC LIMIT :limit OFFSET :offset";
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
