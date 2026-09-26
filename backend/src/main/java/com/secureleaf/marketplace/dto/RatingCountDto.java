package com.secureleaf.marketplace.dto;

/** One star value's count for the rating histogram (D6) — always all five, zero included. */
public record RatingCountDto(int rating, long count) {}
