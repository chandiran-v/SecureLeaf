package com.secureleaf.creator.service;

import com.secureleaf.creator.dto.PayoutDto;
import com.secureleaf.creator.entity.CreatorPayout;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

final class PayoutMapper {

    private PayoutMapper() {}

    static PayoutDto toDto(CreatorPayout p) {
        return new PayoutDto(
                p.getId(),
                p.getCreator().getId(),
                p.getCreator().getDisplayName(),
                p.getCreator().getEmail(),
                p.getAmountPaise(),
                p.getStatus().name(),
                p.getGrossRevenuePaise(),
                p.getPlatformFeePaise(),
                p.getNetPayoutPaise(),
                p.getPayoutMethod(),
                p.getPayoutDestination(),
                p.getPayoutReference(),
                utc(p.getRequestedAt()),
                utc(p.getProcessedAt()),
                p.getNotes());
    }

    private static OffsetDateTime utc(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }
}
