package com.secureleaf.commerce.entity;

/**
 * Payment lifecycle as an explicit state machine (D5).
 *
 * <pre>
 *   PENDING ──► COMPLETED ──► REFUNDED
 *      │            ▲
 *      ▼            │
 *    FAILED ────────┘   (buyer retried on the same gateway order and it succeeded)
 *      │ ▲
 *      └─┘              (another declined attempt — recorded as its own audit event)
 * </pre>
 *
 * Every transition is written to the append-only payment_events table, so this enum
 * also defines what can ever appear in the audit log's from_status → to_status pairs.
 */
public enum PaymentStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED;

    public boolean canTransitionTo(PaymentStatus next) {
        return switch (this) {
            case PENDING, FAILED -> next == COMPLETED || next == FAILED;
            case COMPLETED -> next == REFUNDED;
            case REFUNDED -> false;
        };
    }
}
