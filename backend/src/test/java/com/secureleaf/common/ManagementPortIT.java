package com.secureleaf.common;

import com.secureleaf.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10 D1 — production runs actuator on a separate management port (application-prod.yml:
 * {@code management.server.port: 8081}). This proves that layout end to end with Spring
 * Security in front: Prometheus can scrape and the health probe answers on the management port,
 * while /actuator/prometheus is NOT served on the public application port.
 */
@TestPropertySource(properties = {
        "management.server.port=0",
        "processing.worker.enabled=false"   // secondary context: keep its poller off the shared DB
})
class ManagementPortIT extends AbstractIntegrationTest {

    @LocalServerPort private int appPort;
    @LocalManagementPort private int managementPort;

    @Test
    void prometheusAndHealthAreServedOnTheManagementPortOnly() throws Exception {
        assertThat(managementPort).isNotEqualTo(appPort);

        assertThat(get(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);
        assertThat(get(appPort, "/actuator/prometheus").statusCode()).isNotEqualTo(200);
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
