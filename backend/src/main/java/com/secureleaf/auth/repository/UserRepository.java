package com.secureleaf.auth.repository;

import com.secureleaf.auth.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    Optional<User> findByGoogleSub(String googleSub);

    boolean existsByEmail(String email);

    /** Eagerly fetches roles to avoid LazyInitializationException in GraphQL field resolvers. */
    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesById(Long id);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<User> findWithRolesByGoogleSub(String googleSub);
}
