package com.secureleaf.content.repository;

import com.secureleaf.content.entity.DocumentVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, Long> {

    Optional<DocumentVersion> findByProductIdAndVersionNumber(Long productId, Integer versionNumber);

    Optional<DocumentVersion> findFirstByProductIdOrderByVersionNumberDesc(Long productId);

    /** Processed versions whose links haven't been extracted yet (processed before V8), oldest first. */
    List<DocumentVersion> findTop20ByLinksExtractedAtIsNullAndProcessedAtIsNotNullOrderByIdAsc();
}
