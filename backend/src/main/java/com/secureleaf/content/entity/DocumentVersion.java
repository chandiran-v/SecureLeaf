package com.secureleaf.content.entity;

import com.secureleaf.marketplace.entity.Product;
import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "document_versions")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
public class DocumentVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber = 1;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType = "application/pdf";

    @Column(name = "raw_minio_bucket", nullable = false, length = 100)
    private String rawMinioBucket;

    @Column(name = "raw_minio_object_key", nullable = false, length = 500)
    private String rawMinioObjectKey;

    @Column(name = "page_count")
    private Integer pageCount;

    @Column(name = "thumbnail_minio_key", length = 500)
    private String thumbnailMinioKey;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    /** When the PDF's link annotations were extracted (V8). NULL = not yet: the backfill job picks it up. */
    @Column(name = "links_extracted_at")
    private Instant linksExtractedAt;

    /** D3 — what the creator chose for buyers already on an older version (V12). */
    @Enumerated(EnumType.STRING)
    @Column(name = "update_policy", nullable = false, length = 40)
    private UpdatePolicy updatePolicy = UpdatePolicy.NEW_BUYERS_ONLY;

    /** D3 — set when every ACTIVE entitlement has been moved here (or nothing needed moving). */
    @Column(name = "entitlements_migrated_at")
    private Instant entitlementsMigratedAt;

    /** D5 — soft retirement. A retired version is hidden from the creator's active list and can
     *  never become current or be retried; its row stays for audit and access-log integrity. */
    @Column(name = "retired_at")
    private Instant retiredAt;

}
