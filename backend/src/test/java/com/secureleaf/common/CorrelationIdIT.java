package com.secureleaf.common;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.secureleaf.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 9, D1 — acceptance criterion 1: every response has an {@code X-Correlation-Id} header, a
 * supplied id is echoed back unchanged, and the id survives onto an {@code @Async} thread (proven
 * here as a real, captured Logback event's MDC property map — not just an inspection of
 * {@link com.secureleaf.common.config.MdcTaskDecorator} in isolation).
 */
class CorrelationIdIT extends AbstractIntegrationTest {

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(CorrelationIdIT.class);

    @LocalServerPort
    private int port;

    @Autowired
    @Qualifier("contentProcessingExecutor")
    private ThreadPoolTaskExecutor contentProcessingExecutor;

    @Autowired
    @Qualifier("notificationExecutor")
    private ThreadPoolTaskExecutor notificationExecutor;

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void everyResponse_getsACorrelationId_andEchoesASuppliedOneUnchanged() {
        client().get().uri("/actuator/health")
                .exchange()
                .expectHeader().value("X-Correlation-Id", id -> assertThat(id).isNotBlank());

        client().get().uri("/actuator/health")
                .header("X-Correlation-Id", "test-supplied-id-123")
                .exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "test-supplied-id-123");
    }

    @Test
    void correlationId_survivesOntoBothAsyncExecutors_provenByACapturedLogLine() throws InterruptedException {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(CorrelationIdIT.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);

        try {
            MDC.put("correlationId", "async-propagation-test-id");
            CountDownLatch latch = new CountDownLatch(2);
            // Real, Spring-configured beans (AsyncConfig) — this exercises the exact same
            // TaskDecorator wiring a genuine @Async("contentProcessingExecutor")/
            // @Async("notificationExecutor") call would, not a bare unit test of the decorator.
            contentProcessingExecutor.execute(() -> {
                LOG.info("contentProcessingExecutor task ran");
                latch.countDown();
            });
            notificationExecutor.execute(() -> {
                LOG.info("notificationExecutor task ran");
                latch.countDown();
            });
            assertThat(latch.await(5, TimeUnit.SECONDS)).as("both async tasks ran").isTrue();
        } finally {
            MDC.remove("correlationId");
            logbackLogger.detachAppender(appender);
        }

        assertThat(appender.list)
                .as("one captured log line per executor, both carrying the caller's correlation id")
                .hasSize(2)
                .allSatisfy(event -> assertThat(event.getMDCPropertyMap())
                        .containsEntry("correlationId", "async-propagation-test-id"));
    }
}
