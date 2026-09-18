package com.secureleaf.commerce.entity;

import com.secureleaf.marketplace.entity.Product;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "order_items")
@Getter
@Setter
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    /**
     * Price is captured at the moment of purchase — the product price can change later.
     * Stored in paise (smallest currency unit) to avoid floating-point errors.
     */
    @Column(name = "price_paise", nullable = false)
    private Integer pricePaise;

}
