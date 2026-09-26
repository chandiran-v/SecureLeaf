package com.secureleaf.admin.entity;

/** D7 — the {@code admin_actions.action} column. One value per admin mutation this phase adds. */
public enum AdminActionType {
    SUSPEND_USER,
    REACTIVATE_USER,
    TAKE_DOWN_PRODUCT,
    RESTORE_PRODUCT
}
