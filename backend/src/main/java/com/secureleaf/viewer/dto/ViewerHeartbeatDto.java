package com.secureleaf.viewer.dto;

import java.time.OffsetDateTime;

/** GraphQL {@code ViewerHeartbeat} — the client's every-15s lease renewal (D4). */
public record ViewerHeartbeatDto(String status, OffsetDateTime expiresAt) {}
