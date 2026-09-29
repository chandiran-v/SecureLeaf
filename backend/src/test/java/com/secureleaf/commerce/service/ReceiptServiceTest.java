package com.secureleaf.commerce.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReceiptServiceTest {

    @Test
    void masksAllButTheLastFourCharacters() {
        assertThat(ReceiptService.mask("pay_Abc123XYZ789")).isEqualTo("pay_••••Z789");
    }

    @Test
    void nullAndShortIdsRevealNothing() {
        assertThat(ReceiptService.mask(null)).isNull();
        assertThat(ReceiptService.mask("pay_1")).isEqualTo("••••");
    }
}
