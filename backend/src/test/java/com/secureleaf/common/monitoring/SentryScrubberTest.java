package com.secureleaf.common.monitoring;

import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Message;
import io.sentry.protocol.Request;
import io.sentry.protocol.SentryException;
import io.sentry.protocol.User;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 09D D8 — proves emails, tokens and signed-URL secrets never reach Sentry. */
class SentryScrubberTest {

    private final SentryScrubber scrubber = new SentryScrubber();

    @Test
    void scrub_removesEmailsTokensAndSignedUrlSecrets() {
        String dirty = "user alice@example.com sent Bearer abc.def.ghi and "
                + "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2ln at /tiles/1?sig=SECRET123&exp=99";
        String clean = SentryScrubber.scrub(dirty);

        assertThat(clean).doesNotContain("alice@example.com", "abc.def.ghi", "eyJhbGci", "SECRET123");
        assertThat(clean).contains("[email]", "sig=[redacted]", "exp=99");
    }

    @Test
    void scrub_nullStaysNull() {
        assertThat(SentryScrubber.scrub(null)).isNull();
    }

    @Test
    void execute_cleansMessageExceptionRequestAndUser() {
        SentryEvent event = new SentryEvent();
        Message message = new Message();
        message.setFormatted("failed for bob@example.com");
        event.setMessage(message);
        SentryException exception = new SentryException();
        exception.setValue("token=abc?password=hunter2 for carol@example.com");
        event.setExceptions(List.of(exception));
        Request request = new Request();
        request.setUrl("https://x.test/api/thing?token=zzz");
        request.setQueryString("token=zzz");
        request.setCookies("session=abc");
        event.setRequest(request);
        User user = new User();
        user.setEmail("dave@example.com");
        event.setUser(user);

        SentryEvent out = scrubber.execute(event, new Hint());

        assertThat(out.getMessage().getFormatted()).doesNotContain("bob@example.com");
        assertThat(out.getExceptions().get(0).getValue()).doesNotContain("hunter2", "carol@example.com");
        assertThat(out.getRequest().getUrl()).doesNotContain("zzz");
        assertThat(out.getRequest().getQueryString()).isNull();
        assertThat(out.getRequest().getCookies()).isNull();
        assertThat(out.getUser()).isNull();
    }
}
