package com.secureleaf.commerce;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code payment.*} settings that are not about one gateway (Phase 09B).
 *
 * @param liveEnabled    D8 — the master switch for LIVE money. False until Phase 18: with a
 *                       {@code rzp_live_} key and this off, the app refuses to start.
 * @param reconciliation D7 — the "did we miss a payment?" sweeper.
 */
@ConfigurationProperties(prefix = "payment")
public record PaymentProperties(boolean liveEnabled, Reconciliation reconciliation) {

    public PaymentProperties {
        if (reconciliation == null) reconciliation = new Reconciliation(true, 15, 24 * 60);
    }

    /**
     * @param enabled          whether the timer runs (tests switch it off and call the job directly)
     * @param minAgeMinutes    only orders PENDING for at least this long are looked at — younger ones
     *                         are probably still in the browser's hands
     * @param expireAfterMinutes PENDING orders with nothing captured after this long become FAILED
     */
    public record Reconciliation(boolean enabled, int minAgeMinutes, int expireAfterMinutes) {}
}
