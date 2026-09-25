package com.secureleaf.viewer.repository;

import com.secureleaf.viewer.entity.ViewerAccessLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ViewerAccessLogRepository extends JpaRepository<ViewerAccessLog, Long> {
}
