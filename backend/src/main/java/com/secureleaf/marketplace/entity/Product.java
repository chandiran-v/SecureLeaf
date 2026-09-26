package com.secureleaf.marketplace.entity;

import com.secureleaf.auth.entity.User;
import com.secureleaf.common.entity.BaseEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "products")
@Getter
@Setter
public class Product extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, unique = true, length = 300)
    private String slug;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    @Column(name = "price_paise", nullable = false)
    private Long pricePaise = 0L;

    @Column(name = "free_preview_pages", nullable = false)
    private Integer freePreviewPages = 3;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private ProductStatus status = ProductStatus.DRAFT;

    @Column(name = "total_sales", nullable = false)
    private Integer totalSales = 0;

    @Column(name = "average_rating", precision = 3, scale = 2)
    private BigDecimal averageRating;

    @Column(name = "review_count", nullable = false)
    private Integer reviewCount = 0;

    /** D5 — set together with {@link #takedownReason} by an admin's takeDownProduct; both
     *  null again after restoreProduct. Null/null distinguishes an admin takedown from a
     *  creator's own unpublishProduct, which leaves both columns untouched. */
    @Column(name = "taken_down_at")
    private Instant takenDownAt;

    @Column(name = "takedown_reason", columnDefinition = "TEXT")
    private String takedownReason;

    @Version
    @Column(nullable = false)
    private Long version = 0L;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductTag> tags = new ArrayList<>();

}
