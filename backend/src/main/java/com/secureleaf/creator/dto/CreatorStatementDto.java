package com.secureleaf.creator.dto;

import java.util.List;

/**
 * GraphQL {@code CreatorStatement} (Phase 09C D4). Totals are unsigned magnitudes except
 * {@code netEarningsPaise} (sales net minus refund net), which can in principle be negative.
 */
public record CreatorStatementDto(String month, List<StatementLineDto> lines,
                                  long grossSalesPaise, long refundsPaise, long platformFeePaise,
                                  long netEarningsPaise, long payoutsPaise) {}
