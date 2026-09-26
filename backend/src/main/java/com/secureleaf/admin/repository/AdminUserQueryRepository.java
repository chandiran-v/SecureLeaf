package com.secureleaf.admin.repository;

import com.secureleaf.admin.dto.AdminUserFilterDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D3 — {@code adminUsers}' search/filter/paginate, native SQL for the same reason as
 * {@code ProductSearchRepositoryImpl}: {@code User.roles} is a collection, so fetching it
 * eagerly in the SAME query as a {@code Pageable} would trip Hibernate's HHH000104 in-memory
 * pagination fallback. This class only produces the id page + count (no collection join);
 * {@link com.secureleaf.admin.service.AdminUserService} does the step-3 entity fetch via
 * {@code UserRepository.findAllByIdIn} (which DOES eagerly fetch roles, safely, because it has
 * no Pageable) and re-orders the result back to this class's id order.
 */
@Repository
public class AdminUserQueryRepository {

    @PersistenceContext
    private EntityManager entityManager;

    public IdPage searchUserIds(AdminUserFilterDto filter, Pageable pageable) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        Map<String, Object> params = new LinkedHashMap<>();

        if (filter != null && filter.search() != null && !filter.search().isBlank()) {
            where.append(" AND (LOWER(u.email) LIKE :search OR LOWER(u.display_name) LIKE :search) ");
            params.put("search", "%" + filter.search().trim().toLowerCase() + "%");
        }
        if (filter != null && filter.status() != null) {
            // Explicit CAST(... AS account_status) — a bound String parameter has no type of
            // its own in a native query (unlike JPQL, where Hibernate infers the column's
            // type), so Postgres would otherwise reject "enum_column = text" outright. Written
            // as CAST(...) rather than the terser `:status::account_status` because Hibernate's
            // named-parameter parser greedily swallows the `::type` suffix into the parameter
            // NAME itself (UnknownParameterException: no parameter named ":status::account_status"),
            // not just the SQL Postgres eventually sees.
            where.append(" AND u.account_status = CAST(:status AS account_status) ");
            params.put("status", filter.status().name());
        }
        if (filter != null && filter.role() != null) {
            where.append(" AND EXISTS (SELECT 1 FROM user_roles ur WHERE ur.user_id = u.id AND ur.role = CAST(:role AS user_role)) ");
            params.put("role", filter.role().name());
        }

        List<Long> ids = fetchIds(where.toString(), params, pageable);
        long total = fetchCount(where.toString(), params);
        return new IdPage(ids, total);
    }

    @SuppressWarnings("unchecked")
    private List<Long> fetchIds(String where, Map<String, Object> params, Pageable pageable) {
        String sql = "SELECT u.id FROM users u" + where
                + " ORDER BY u.created_at DESC, u.id DESC LIMIT :limit OFFSET :offset";
        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        query.setParameter("limit", pageable.getPageSize());
        query.setParameter("offset", pageable.getOffset());
        List<Object> raw = query.getResultList();
        return raw.stream().map(o -> ((Number) o).longValue()).toList();
    }

    private long fetchCount(String where, Map<String, Object> params) {
        String sql = "SELECT COUNT(*) FROM users u" + where;
        Query query = entityManager.createNativeQuery(sql);
        params.forEach(query::setParameter);
        return ((Number) query.getSingleResult()).longValue();
    }
}
