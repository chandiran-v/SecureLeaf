package com.secureleaf.auth.repository;

import com.secureleaf.auth.entity.UserRole;
import com.secureleaf.auth.entity.UserRoleId;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleRepository extends JpaRepository<UserRole, UserRoleId> {
}
