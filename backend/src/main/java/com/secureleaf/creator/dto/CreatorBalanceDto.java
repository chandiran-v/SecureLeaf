package com.secureleaf.creator.dto;

/**
 * GraphQL {@code CreatorBalance} (Phase 09C D1). Derived from events on every read — never stored.
 *
 * @param availablePaise        cleared earnings minus every payout that is not REJECTED
 * @param pendingPaise          earnings still inside the hold period
 * @param lifetimeEarningsPaise all non-refunded earnings, ever
 * @param paidOutPaise          payouts already marked PAID
 */
public record CreatorBalanceDto(long availablePaise, long pendingPaise, long lifetimeEarningsPaise, long paidOutPaise) {}
