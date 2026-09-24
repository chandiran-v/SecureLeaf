package com.secureleaf.commerce;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code commerce.*} settings, bound from application.yml.
 *
 * @param platformFeeBps platform commission in basis points (1 bps = 0.01%; 1000 = 10%, D7).
 *                       Basis points keep the rate an integer, so fee maths never touches
 *                       floating point.
 * @param currency       ISO currency code sent to the gateway.
 */
@ConfigurationProperties(prefix = "commerce")
public record CommerceProperties(int platformFeeBps, String currency) {

    /**
     * Platform's cut of a price, rounded half-up to the nearest paisa (D7).
     * The creator's share is then {@code price - fee}: deriving one side by subtraction
     * guarantees the two always add back up to the price exactly — no paisa appears or
     * vanishes through rounding both halves independently.
     */
    public long platformFeeFor(long pricePaise) {
        return (pricePaise * platformFeeBps + 5_000) / 10_000;
    }
}
