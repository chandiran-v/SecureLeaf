package com.secureleaf.common.mail;

import jakarta.mail.internet.MimeMessage;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends mail through Brevo's HTTPS API instead of SMTP. Render's free tier blocks outbound SMTP
 * ports (25/465/587), so on that host the normal Spring Boot SMTP sender cannot work.
 *
 * <p>ADAPTER PATTERN. It implements {@link JavaMailSender}, the interface every mailer here already
 * depends on ({@code PasswordResetMailer}, {@code NotificationDispatcher}), so none of them change:
 * {@link BrevoMailConfig} registers this bean and Spring Boot's own SMTP sender backs off (it is only
 * auto-configured when no {@code JavaMailSender} exists).
 *
 * <p>Only plain-text {@link SimpleMailMessage} is supported, because that is all this app sends. The
 * MIME methods throw rather than silently drop mail.
 *
 * <p>Error messages carry the HTTP status and Brevo's error code only, never Brevo's message text:
 * it can echo the recipient's address, and the mailers log {@code e.getMessage()} (Phase 9 D2: never
 * log an email address).
 */
public class BrevoApiMailSender implements JavaMailSender {

    private static final Pattern ERROR_CODE = Pattern.compile("\"code\"\\s*:\\s*\"([a-z_]+)\"");

    private final RestClient restClient;
    private final String apiKey;

    public BrevoApiMailSender(RestClient restClient, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("mail.brevo.api-key (BREVO_API_KEY) must be set when mail.transport=brevo-api");
        }
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    @Override
    public void send(SimpleMailMessage simpleMessage) {
        send(new SimpleMailMessage[]{simpleMessage});
    }

    @Override
    public void send(SimpleMailMessage... simpleMessages) {
        for (SimpleMailMessage message : simpleMessages) {
            try {
                restClient.post()
                        .uri("/smtp/email")
                        .header("api-key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(body(message))
                        .retrieve()
                        .toBodilessEntity();
            } catch (RestClientResponseException e) {
                throw new MailSendException("Brevo API returned HTTP " + e.getStatusCode().value()
                        + errorCode(e.getResponseBodyAsString()));
            } catch (RuntimeException e) {
                throw new MailSendException("Brevo API call failed: " + e.getClass().getSimpleName());
            }
        }
    }

    /** Brevo's transactional-email request: {sender, to[], subject, textContent}. */
    static Map<String, Object> body(SimpleMailMessage message) {
        if (message.getFrom() == null || message.getTo() == null || message.getTo().length == 0) {
            throw new MailSendException("Message needs a from address and at least one recipient");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sender", Map.of("email", message.getFrom()));
        List<Map<String, String>> to = Arrays.stream(message.getTo()).map(addr -> Map.of("email", addr)).toList();
        body.put("to", to);
        body.put("subject", message.getSubject() == null ? "" : message.getSubject());
        body.put("textContent", message.getText() == null ? "" : message.getText());
        return body;
    }

    private static String errorCode(String responseBody) {
        Matcher m = ERROR_CODE.matcher(responseBody == null ? "" : responseBody);
        return m.find() ? " (" + m.group(1) + ")" : "";
    }

    // ── MIME is not used by this app ─────────────────────────────────────────

    @Override
    public MimeMessage createMimeMessage() {
        throw new UnsupportedOperationException("BrevoApiMailSender sends plain-text SimpleMailMessage only");
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) {
        throw new UnsupportedOperationException("BrevoApiMailSender sends plain-text SimpleMailMessage only");
    }

    @Override
    public void send(MimeMessage mimeMessage) {
        throw new UnsupportedOperationException("BrevoApiMailSender sends plain-text SimpleMailMessage only");
    }

    @Override
    public void send(MimeMessage... mimeMessages) {
        throw new UnsupportedOperationException("BrevoApiMailSender sends plain-text SimpleMailMessage only");
    }

    @Override
    public void send(MimeMessagePreparator mimeMessagePreparator) {
        throw new UnsupportedOperationException("BrevoApiMailSender sends plain-text SimpleMailMessage only");
    }

    @Override
    public void send(MimeMessagePreparator... mimeMessagePreparators) {
        throw new UnsupportedOperationException("BrevoApiMailSender sends plain-text SimpleMailMessage only");
    }
}
