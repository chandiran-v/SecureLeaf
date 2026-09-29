package com.secureleaf.common.monitoring;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Phase 09D, D8 — removes personal data and credentials from every event BEFORE it leaves the
 * server. Sentry's starter picks up any {@link SentryOptions.BeforeSendCallback} bean.
 *
 * Why: an exception message can carry an email address ("User a@b.com not found"), a bearer/JWT
 * token, or a signed tile URL ({@code ?sig=...}). Those must never be stored by a third party.
 * With {@code sentry.send-default-pii=false} the SDK already skips IPs, cookies and headers; this
 * callback is the second layer that cleans free text.
 *
 * The bean is created whether or not SENTRY_DSN is set; with no DSN the SDK is disabled and the
 * callback simply never runs (zero behaviour change).
 */
@Component
public class SentryScrubber implements SentryOptions.BeforeSendCallback {

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+");
    /** Query-string secrets: signed tile URLs, tokens, passwords. */
    private static final Pattern SECRET_PARAM =
            Pattern.compile("(?i)([?&](?:sig|signature|token|ticket|access_token|refresh_token|password|key|secret)=)[^&\\s\"']*");

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        event.setUser(null);

        Message message = event.getMessage();
        if (message != null) {
            message.setMessage(scrub(message.getMessage()));
            message.setFormatted(scrub(message.getFormatted()));
        }
        if (event.getExceptions() != null) {
            for (SentryException exception : event.getExceptions()) {
                exception.setValue(scrub(exception.getValue()));
            }
        }
        Request request = event.getRequest();
        if (request != null) {
            request.setUrl(scrub(request.getUrl()));
            request.setQueryString(null);
            request.setCookies(null);
            request.setHeaders(null);
            request.setData(null);
        }
        return event;
    }

    /** Package-visible for the unit test. */
    static String scrub(String text) {
        if (text == null) {
            return null;
        }
        String out = SECRET_PARAM.matcher(text).replaceAll("$1[redacted]");
        out = JWT.matcher(out).replaceAll("[jwt]");
        out = BEARER.matcher(out).replaceAll("Bearer [redacted]");
        return EMAIL.matcher(out).replaceAll("[email]");
    }
}
