package com.secureleaf.auth.repository;

import com.secureleaf.auth.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /** AUTH-07 — the reset link's token is hashed before storage; this looks it up by that hash. */
    Optional<User> findByResetTokenHash(String resetTokenHash);

    Optional<User> findByGoogleSub(String googleSub);

    boolean existsByEmail(String email);

    /** Eagerly fetches roles to avoid LazyInitializationException in GraphQL field resolvers. */
    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesById(Long id);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesByGoogleSub(String googleSub);

    /**
     * {@code SELECT … FOR UPDATE} on the user row — a per-buyer mutex for checkout (D2).
     *
     * Two concurrent initiateOrder calls from the same buyer (double-click, two tabs)
     * would otherwise both see "no open order yet" and both create one — a classic
     * check-then-act race. Holding this row lock for the rest of the transaction makes
     * the second request wait until the first commits, so it then *sees* the first
     * one's order. Other buyers are unaffected: they lock different rows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
