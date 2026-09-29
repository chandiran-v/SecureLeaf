package com.secureleaf.commerce.dto;

/** GraphQL {@code PlatformInfo} (Phase 09B D8) — public, non-secret facts the UI needs on every page. */
public record PlatformInfoDto(String paymentMode, String supportEmail) {}
