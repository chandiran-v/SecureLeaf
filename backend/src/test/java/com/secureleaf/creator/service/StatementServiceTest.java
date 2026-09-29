package com.secureleaf.creator.service;

import com.secureleaf.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatementServiceTest {

    @Test
    void csvCellQuotesAndDoublesEmbeddedQuotes() {
        assertThat(StatementService.csvCell("Guide, \"2nd\" edition")).isEqualTo("\"Guide, \"\"2nd\"\" edition\"");
    }

    @Test
    void csvCellDefusesSpreadsheetFormulas() {
        for (String evil : new String[]{"=1+1", "+cmd", "-2", "@SUM(A1)"}) {
            assertThat(StatementService.csvCell(evil)).startsWith("\"'" + evil.charAt(0));
        }
        assertThat(StatementService.csvCell("Order #1")).isEqualTo("\"Order #1\"");
    }

    @Test
    void monthMustBeYyyyMm() {
        assertThat(StatementService.parse("2026-03").toString()).isEqualTo("2026-03");
        for (String bad : new String[]{null, "", "2026-3", "2026-13", "March", "2026-03-01"}) {
            assertThatThrownBy(() -> StatementService.parse(bad)).isInstanceOf(BusinessException.class);
        }
    }
}
