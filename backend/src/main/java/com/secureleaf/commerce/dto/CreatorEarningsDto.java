package com.secureleaf.commerce.dto;

/**
 * GraphQL {@code CreatorEarnings} (PAY-10). Amounts are {@code long} paise and exposed as the
 * {@code Long} scalar, not {@code Int}: GraphQL's Int is a signed 32-bit integer, which tops
 * out at ₹2.14 crore in paise — a successful creator's lifetime sales can exceed that.
 */
public record CreatorEarningsDto(
        long salesCount,
        long grossSalesPaise,
        long platformFeePaise,
        long netEarningsPaise
) {}
