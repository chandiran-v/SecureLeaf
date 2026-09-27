package com.secureleaf.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * {@code admin.emails} — bound from the comma-separated {@code ADMIN_EMAILS} env var (D1).
 *
 * WHY CONFIG, NOT A GRAPHQL MUTATION (D1)
 * There is deliberately no {@code grantAdmin} mutation anywhere in the schema. If granting
 * ADMIN were an API call, the very first question a security review asks is "which existing
 * role can call it, and what stops a CREATOR (or a bug in that check) from calling it on
 * themselves?" — a privilege-escalation path that has to be defended by application code, and
 * defended perfectly forever. Making it config-only removes the question: escalating a user to
 * ADMIN requires editing deployment configuration and restarting the process, which is already
 * gated by whatever access controls protect the deployment itself (e.g. who can edit Render's
 * environment variables) — infrastructure the app doesn't need to reimplement.
 */
@ConfigurationProperties(prefix = "admin")
public record AdminProperties(List<String> emails) {

    public AdminProperties {
        emails = emails == null ? List.of() : emails;
    }
}
