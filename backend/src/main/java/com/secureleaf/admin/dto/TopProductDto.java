package com.secureleaf.admin.dto;

/** D6 — one row of {@code PlatformStats.topProducts}. */
public record TopProductDto(Long productId, String title, int salesCount, long grossSalesPaise) {}
