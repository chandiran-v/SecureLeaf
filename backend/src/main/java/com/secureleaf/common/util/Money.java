package com.secureleaf.common.util;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

/** Display helpers for paise amounts. Arithmetic always stays in integer paise. */
public final class Money {

    private Money() {}

    /** 49900 → "₹499", 49950 → "₹499.50", 0 → "Free". Only for human-readable text. */
    public static String formatRupees(long paise) {
        if (paise == 0) return "Free";
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        format.setMinimumFractionDigits(paise % 100 == 0 ? 0 : 2);
        format.setMaximumFractionDigits(2);
        return "₹" + format.format(BigDecimal.valueOf(paise, 2));
    }
}
