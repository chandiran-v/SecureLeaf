package com.secureleaf.content.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One clickable link on a rendered page (V8). The rectangle is stored as fractions of the page
 * image (0..1, origin top-left, rotation applied) so it lines up at any display size or zoom.
 */
@Entity
@Table(name = "content_page_links")
@Getter
@Setter
public class ContentPageLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_page_id", nullable = false)
    private ContentPage contentPage;

    @Column(name = "left_ratio", nullable = false)
    private Double leftRatio;

    @Column(name = "top_ratio", nullable = false)
    private Double topRatio;

    @Column(name = "width_ratio", nullable = false)
    private Double widthRatio;

    @Column(name = "height_ratio", nullable = false)
    private Double heightRatio;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_type", nullable = false, length = 10)
    private PageLinkType linkType;

    @Column(name = "target_url", length = 2048)
    private String targetUrl;

    @Column(name = "target_page")
    private Integer targetPage;
}
