package com.secureleaf.auth;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.context.annotation.Primary;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test replacement for the real {@link JavaMailSender} — same {@code @Primary} trick as
 * {@code InMemoryStorageService} and {@code RecordingNotificationPublisher}, so the suite needs
 * no real SMTP server (Mailpit in dev). Records every {@link SimpleMailMessage} sent by
 * {@code PasswordResetMailer} so tests can assert on subject/recipient without a mail server.
 */
@Service
@Primary
public class RecordingMailSender implements JavaMailSender {

    private final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();
    private final Session session = Session.getInstance(new Properties());

    public List<SimpleMailMessage> sent() {
        return List.copyOf(sent);
    }

    public void clear() {
        sent.clear();
    }

    @Override
    public MimeMessage createMimeMessage() {
        return new MimeMessage(session);
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contentStream) throws MailException {
        try {
            return new MimeMessage(session, contentStream);
        } catch (MessagingException e) {
            throw new MailParseException(e);
        }
    }

    @Override
    public void send(MimeMessage mimeMessage) throws MailException {
        // Unused — PasswordResetMailer sends SimpleMailMessage, matching NotificationDispatcher.
    }

    @Override
    public void send(MimeMessage... mimeMessages) throws MailException {
    }

    @Override
    public void send(MimeMessagePreparator mimeMessagePreparator) throws MailException {
    }

    @Override
    public void send(MimeMessagePreparator... mimeMessagePreparators) throws MailException {
    }

    @Override
    public void send(SimpleMailMessage simpleMessage) throws MailException {
        sent.add(simpleMessage);
    }

    @Override
    public void send(SimpleMailMessage... simpleMessages) throws MailException {
        sent.addAll(List.of(simpleMessages));
    }
}
