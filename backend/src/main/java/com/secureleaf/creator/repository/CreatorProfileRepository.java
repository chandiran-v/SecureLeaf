package com.secureleaf.creator.repository;

import com.secureleaf.creator.entity.CreatorProfile;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreatorProfileRepository extends JpaRepository<CreatorProfile, Long> {
}
