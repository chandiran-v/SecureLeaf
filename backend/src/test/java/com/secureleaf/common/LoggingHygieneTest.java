package com.secureleaf.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9, D2 — a cheap, static guard against the one class of logging mistake that matters most:
 * a log statement that hands a raw secret to SLF4J.
 *
 * HOW IT WORKS (AND ITS LIMITS)
 * This scans every {@code .java} file under {@code src/main/java} for {@code log.info/warn/error/
 * debug(...)} calls, strips out string literals (so message text like {@code "Invalid token"} is
 * never mistaken for logging a value), and flags any call whose remaining arguments contain one of
 * a fixed list of variable names that hold a raw secret in this codebase (a password, a signing
 * secret, or an unhashed token) used directly — not one of its fields.
 *
 * This is deliberately narrow, not a general secret scanner: it is a name-based check, so a
 * variable holding a secret under an unlisted name would slip through, and it does not understand
 * types — it cannot tell that {@code tokenHash} (a SHA-256 digest, safe to log) is fine and
 * {@code rawToken} (the actual credential) is not, except by name. Both are exactly why D2 also
 * says "or document it as a review checklist item" — this test catches the careless, common
 * mistake automatically; a human reviewer is still the backstop for anything cleverer.
 */
class LoggingHygieneTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of("src", "main", "java");

    /**
     * Exact identifiers that hold a raw secret somewhere in this codebase today. A match on
     * {@code token} alone would also match harmless code like {@code token.getUser().getId()} (a
     * RefreshToken *entity*, not its value) — the "not followed by a member access" rule below is
     * what tells those two apart.
     */
    private static final List<String> FORBIDDEN_IDENTIFIERS = List.of(
            "password", "rawPassword", "newPassword", "currentPassword",
            "token", "rawToken", "idToken", "accessToken", "refreshToken", "sessionToken",
            "secret", "clientSecret", "signingSecret", "keySecret", "webhookSecret", "jwtSecret");

    private static final Pattern LOG_CALL = Pattern.compile(
            "log\\.(?:info|warn|error|debug)\\((.*?)\\);", Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");

    @Test
    void noLogStatementPassesARawSecretIdentifier() throws IOException {
        List<String> violations = new ArrayList<>();

        try (var files = Files.walk(MAIN_SOURCE_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(path -> {
                String source;
                try {
                    source = Files.readString(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                Matcher logCall = LOG_CALL.matcher(source);
                while (logCall.find()) {
                    String args = STRING_LITERAL.matcher(logCall.group(1)).replaceAll("\"\"");
                    for (String identifier : FORBIDDEN_IDENTIFIERS) {
                        // \b...\b so `token` doesn't match `sessionToken`, `refreshToken`, etc. —
                        // those are separately listed. Negative lookahead excludes `token.getX()`
                        // (a field/member access, i.e. extracting one non-secret piece of an
                        // object) so only the bare identifier — the raw value itself — trips this.
                        Pattern identifierUsedBare = Pattern.compile("\\b" + identifier + "\\b(?!\\s*\\.)");
                        if (identifierUsedBare.matcher(args).find()) {
                            violations.add(path + ": log call passes raw `" + identifier + "` — " + logCall.group().strip());
                        }
                    }
                }
            });
        }

        assertThat(violations)
                .as("Never log a password, token or secret value directly (D2). If this is a false "
                        + "positive — a variable named like a secret that safely holds something else, "
                        + "or a chained field access this regex didn't recognise — rename the variable "
                        + "or adjust FORBIDDEN_IDENTIFIERS/the bare-usage check, don't just delete the assertion.")
                .isEmpty();
    }
}
