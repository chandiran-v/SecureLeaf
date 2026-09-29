package com.secureleaf.creator.dto;

import java.time.Instant;

/** One sale or refund as read from the database (Phase 09C D4); the service turns it into a statement line. */
public record StatementRow(Long orderId, String productTitle, Long pricePaise, Long platformFeePaise,
                           Long creatorEarningsPaise, Instant at) {}
