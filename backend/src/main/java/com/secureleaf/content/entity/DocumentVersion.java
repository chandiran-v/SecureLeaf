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

}
