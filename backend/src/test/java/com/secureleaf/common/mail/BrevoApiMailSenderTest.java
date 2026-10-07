package com.secureleaf.common.mail;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The Brevo HTTPS mail adapter, tested without a network (same approach as RazorpayGatewayTest). */
class BrevoApiMailSenderTest {

    private static final String BASE = "https://api.brevo.test/v3";

    private MockRestServiceServer server;
    private BrevoApiMailSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new BrevoApiMailSender(builder.build(), "test-api-key");
    }

    @Test
    void send_postsTheTransactionalEmailRequestWithTheApiKey() {
        server.expect(requestTo(BASE + "/smtp/email"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("api-key", "test-api-key"))
                .andExpect(jsonPath("$.sender.email").value("no-reply@secureleaf.test"))
                .andExpect(jsonPath("$.to[0].email").value("reader@example.com"))
                .andExpect(jsonPath("$.subject").value("Reset your password"))
                .andExpect(jsonPath("$.textContent").value("Click the link"))
                .andRespond(withSuccess("{\"messageId\":\"<1@brevo>\"}", MediaType.APPLICATION_JSON));

        sender.send(message());

        server.verify();
    }

    @Test
    void apiError_becomesMailSendException_withoutTheRecipientAddress() {
        // Brevo's error text can echo the address; the mailers log e.getMessage() (Phase 9 D2).
        server.expect(requestTo(BASE + "/smtp/email"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"invalid_parameter\",\"message\":\"reader@example.com is not valid\"}"));

        assertThatThrownBy(() -> sender.send(message()))
                .isInstanceOf(MailSendException.class)
                .hasMessage("Brevo API returned HTTP 400 (invalid_parameter)")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("reader@example.com"));
    }

    @Test
    void missingApiKey_failsAtStartup_notAtTheFirstEmail() {
        assertThatThrownBy(() -> new BrevoApiMailSender(RestClient.create(), " "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BREVO_API_KEY");
    }

    private static SimpleMailMessage message() {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom("no-reply@secureleaf.test");
        message.setTo("reader@example.com");
        message.setSubject("Reset your password");
        message.setText("Click the link");
        return message;
    }
}
