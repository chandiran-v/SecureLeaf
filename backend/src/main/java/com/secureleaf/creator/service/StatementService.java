package com.secureleaf.creator.service;

import com.secureleaf.commerce.entity.OrderStatus;
import com.secureleaf.commerce.repository.OrderItemRepository;
import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import com.secureleaf.creator.dto.CreatorStatementDto;
import com.secureleaf.creator.dto.StatementLineDto;
import com.secureleaf.creator.dto.StatementRow;
import com.secureleaf.creator.entity.CreatorPayout;
import com.secureleaf.creator.entity.PayoutStatus;
import com.secureleaf.creator.repository.CreatorPayoutRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Monthly creator statement (Phase 09C D4): sales, refunds, fees and payouts for one calendar month,
 * with totals. The GraphQL query and the CSV download both come from {@link #statement}, so the two
 * can never disagree.
 *
 * The month is an Indian calendar month ({@link #STATEMENT_ZONE}), because that's how the creator
 * and their accountant think about "January" — a sale at 23:30 IST on 31 Jan belongs to January
 * even though it is already 1 Feb in UTC.
 */
@Service
@RequiredArgsConstructor
public class StatementService {

    static final ZoneId STATEMENT_ZONE = ZoneId.of("Asia/Kolkata");

    private final OrderItemRepository orderItemRepository;
    private final CreatorPayoutRepository payoutRepository;

    @Transactional(readOnly = true)
    public CreatorStatementDto statement(Long creatorId, String month) {
        YearMonth ym = parse(month);
        Instant from = ym.atDay(1).atStartOfDay(STATEMENT_ZONE).toInstant();
        Instant to = ym.plusMonths(1).atDay(1).atStartOfDay(STATEMENT_ZONE).toInstant();

        List<StatementLineDto> lines = new ArrayList<>();
        long grossSales = 0, refunds = 0, fees = 0, net = 0, payouts = 0;

        for (StatementRow r : orderItemRepository.findSalesRows(creatorId,
                List.of(OrderStatus.COMPLETED, OrderStatus.REFUNDED), from, to)) {
            lines.add(new StatementLineDto(r.at().atZone(STATEMENT_ZONE).toOffsetDateTime(), "SALE",
                    "Order #" + r.orderId() + " — " + r.productTitle(),
                    r.pricePaise(), r.platformFeePaise(), r.creatorEarningsPaise()));
            grossSales += r.pricePaise();
            fees += r.platformFeePaise();
            net += r.creatorEarningsPaise();
        }
        for (StatementRow r : orderItemRepository.findRefundRows(creatorId, from, to)) {
            lines.add(new StatementLineDto(r.at().atZone(STATEMENT_ZONE).toOffsetDateTime(), "REFUND",
                    "Refund of order #" + r.orderId() + " — " + r.productTitle(),
                    -r.pricePaise(), -r.platformFeePaise(), -r.creatorEarningsPaise()));
            refunds += r.pricePaise();
            fees -= r.platformFeePaise();
            net -= r.creatorEarningsPaise();
        }
        for (CreatorPayout p : payoutRepository
                .findByCreatorIdAndStatusAndProcessedAtGreaterThanEqualAndProcessedAtLessThanOrderByProcessedAt(
                        creatorId, PayoutStatus.PAID, from, to)) {
            lines.add(new StatementLineDto(p.getProcessedAt().atZone(STATEMENT_ZONE).toOffsetDateTime(), "PAYOUT",
                    "Payout #" + p.getId() + " to " + p.getPayoutDestination()
                            + (p.getPayoutReference() != null ? " (ref " + p.getPayoutReference() + ")" : ""),
                    0, 0, -p.getAmountPaise()));
            payouts += p.getAmountPaise();
        }
        lines.sort(Comparator.comparing(StatementLineDto::date));
        return new CreatorStatementDto(ym.toString(), lines, grossSales, refunds, fees, net, payouts);
    }

    /** CSV of the same data. Amounts stay integer paise (no float rounding); see {@link #csvCell}. */
    @Transactional(readOnly = true)
    public String statementCsv(Long creatorId, String month) {
        CreatorStatementDto s = statement(creatorId, month);
        StringBuilder sb = new StringBuilder("date,type,description,gross_paise,fee_paise,net_paise\r\n");
        for (StatementLineDto l : s.lines()) {
            sb.append(csvCell(l.date().toString())).append(',')
              .append(l.type()).append(',')
              .append(csvCell(l.description())).append(',')
              .append(l.grossPaise()).append(',')
              .append(l.feePaise()).append(',')
              .append(l.netPaise()).append("\r\n");
        }
        sb.append(",TOTAL,Gross sales,").append(s.grossSalesPaise()).append(",,\r\n");
        sb.append(",TOTAL,Refunds,").append(-s.refundsPaise()).append(",,\r\n");
        sb.append(",TOTAL,Platform fee (net of refunds),,").append(s.platformFeePaise()).append(",\r\n");
        sb.append(",TOTAL,Net earnings,,,").append(s.netEarningsPaise()).append("\r\n");
        sb.append(",TOTAL,Payouts,,,").append(-s.payoutsPaise()).append("\r\n");
        return sb.toString();
    }

    static YearMonth parse(String month) {
        try {
            if (month == null || !month.matches("\\d{4}-\\d{2}")) throw new DateTimeParseException("bad", "", 0);
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "Month must look like 2026-03 (YYYY-MM).");
        }
    }

    /**
     * RFC 4180 quoting, plus CSV-injection defence: product titles are creator-controlled, and a cell
     * starting with = + - @ is executed as a formula when the file is opened in Excel/Sheets.
     * A leading apostrophe makes the spreadsheet treat it as text.
     */
    static String csvCell(String value) {
        String v = value == null ? "" : value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
