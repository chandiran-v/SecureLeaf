package com.secureleaf.admin.dto;

/** Public-safe projection of the acting admin — same reasoning as {@code CreatorSummaryDto}. */
public record AdminActorDto(Long id, String displayName) {}
