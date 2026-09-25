package com.secureleaf.viewer.entity;

/** D10 — why a viewer session ended. Mirrors the DB CHECK constraint added in V5. */
public enum ViewerSessionEndReason {
    /** The buyer explicitly closed the viewer ({@code endViewerSession}). */
    CLOSED,
    /** A second {@code startViewerSession} for the same buyer+product took over (D3). */
    SUPERSEDED,
    /** The heartbeat lease lapsed and the sweeper reaped the row (D4). */
    EXPIRED,
    /** An admin/creator action (e.g. entitlement revocation) cut the session short. */
    REVOKED
}
