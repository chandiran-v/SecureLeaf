package com.secureleaf.marketplace.dto;

/**
 * Data Transfer Object for the GraphQL {@code Category} type.
 */
public record CategoryDto(Long id, String name, String slug) {}
