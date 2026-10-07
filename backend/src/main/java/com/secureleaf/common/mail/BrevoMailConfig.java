package com.secureleaf.common.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.client.RestClient;

/**
 * {@code mail.transport=brevo-api} (the Render profile sets it): mail goes over HTTPS to Brevo.
 * Unset (every other environment): Spring Boot's SMTP sender, configured by {@code spring.mail.*}.
 *
 * <p>Timeouts like the Razorpay client (Phase 09B): connect 3 s, read 10 s. Sends already run on
 * the notification executor after commit, so a slow Brevo never holds a request or a transaction.
 */
@Configuration
@ConditionalOnProperty(name = "mail.transport", havingValue = "brevo-api")
public class BrevoMailConfig {

    static final int CONNECT_TIMEOUT_MS = 3_000;
    static final int READ_TIMEOUT_MS = 10_000;

    @Bean
    JavaMailSender brevoApiMailSender(@Value("${mail.brevo.base-url:https://api.brevo.com/v3}") String baseUrl,
                                      @Value("${mail.brevo.api-key:}") String apiKey) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new BrevoApiMailSender(RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build(), apiKey);
    }
}
