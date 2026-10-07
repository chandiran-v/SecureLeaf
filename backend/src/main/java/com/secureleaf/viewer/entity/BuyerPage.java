package com.secureleaf.viewer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One buyer's pre-watermarked copy of one page (one variant) of the version their entitlement is
 * pinned to. See {@code V14__buyer_pages.sql} and {@link com.secureleaf.viewer.prewatermark.BuyerPageReconciler}.
 *
 * Plain id columns, not {@code @ManyToOne}: this table is an index of stored objects. Nothing ever
 * navigates from a page row to the entitlement, and the background jobs that write it run outside
 * any request, where lazy associations are a trap.
 */
@Entity
@Table(name = "buyer_pages")
@Getter
@Setter
public class BuyerPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entitlement_id", nullable = false)
    private Long entitlementId;

    @Column(name = "document_version_id", nullable = false)
    private Long documentVersionId;

    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    @Column(name = "variant", nullable = false, length = 16)
    private String variant;

    @Column(name = "watermark_version", nullable = false, length = 16)
    private String watermarkVersion;

    @Column(name = "bucket_name", nullable = false, length = 100)
    private String bucketName;

    @Column(name = "object_key", nullable = false, length = 500)
    private String objectKey;

    @Column(name = "file_size_bytes")
    private Long fileSizeBytes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
