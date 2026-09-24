package com.secureleaf.auth.dto;

/**
 * Returned by login, refresh, and googleLogin mutations.
 * Field names must match the GraphQL {@code AuthPayload} type.
 */
public record AuthPayload(String accessToken, String refreshToken, UserDto user) {}
