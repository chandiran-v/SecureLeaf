package com.secureleaf.marketplace.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "product_tags")
@IdClass(ProductTagId.class)
@Getter
@Setter
public class ProductTag {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Id
    @Column(nullable = false, length = 50)
    private String tag;

}
