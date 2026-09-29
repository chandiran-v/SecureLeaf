package com.secureleaf.creator.entity;

public enum PayoutStatus {
    REQUESTED,
    APPROVED,
    PROCESSING,
    PAID,
    REJECTED;

    /**
     * D3 — the payout state machine. PROCESSING exists in the database enum since V1 (reserved for
     * automated payouts, out of scope this phase), so nothing moves into or out of it here.
     * <pre>
     *   REQUESTED ──► APPROVED ──► PAID          (terminal: money has left)
     *       │            │
     *       └────────────┴──────► REJECTED       (terminal: balance released)
     * </pre>
     */
    public boolean canTransitionTo(PayoutStatus next) {
        return switch (this) {
            case REQUESTED -> next == APPROVED || next == REJECTED;
            case APPROVED -> next == PAID || next == REJECTED;
            case PROCESSING, PAID, REJECTED -> false;
        };
    }

    /** An "open" request still holds the creator's money and blocks a new request (D2). */
    public boolean isOpen() {
        return this == REQUESTED || this == APPROVED || this == PROCESSING;
    }
}
