package com.secureleaf.commerce.entity;

/**
 * Order lifecycle as an explicit state machine (D5).
 *
 * <pre>
 *   PENDING ──► COMPLETED ──► REFUNDED
 *      │
 *      └─────► FAILED        (superseded / abandoned — a declined card does NOT fail the
 *                             order: Razorpay lets the buyer retry on the same order)
 * </pre>
 *
 * Encoding the allowed transitions next to the states means an illegal jump
 * (e.g. COMPLETED → PENDING) fails loudly in one place instead of silently
 * corrupting data wherever someone happened to call setStatus().
 */
public enum OrderStatus {
    PENDING,
    COMPLETED,
    FAILED,
    REFUNDED;

    public boolean canTransitionTo(OrderStatus next) {
        return switch (this) {
            case PENDING -> next == COMPLETED || next == FAILED;
            case COMPLETED -> next == REFUNDED;
            case FAILED, REFUNDED -> false;   // terminal states
        };
    }
}
