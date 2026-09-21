package com.secureleaf.auth.dto;

/**
 * Input for the {@code becomeCreator} GraphQL mutation.
 * All fields are optional — a user can become a creator without filling in
 * bio or payout details immediately (those can be updated later).
 */
public record BecomeCreatorInput(String bio, String payoutEmail, String payoutUpi) {}
