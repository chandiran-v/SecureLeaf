package com.secureleaf.content.entity;

/** Phase 15, D3 — chosen per version at upload time by the creator. */
public enum UpdatePolicy {
    /** Existing buyers keep the version they bought; only new purchases get this one. */
    NEW_BUYERS_ONLY,
    /** Once processing succeeds, every ACTIVE entitlement for the product is moved to this version. */
    FREE_UPDATE_FOR_EXISTING
}
