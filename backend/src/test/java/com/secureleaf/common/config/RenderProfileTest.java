package com.secureleaf.common.config;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the security-relevant parts of {@code application-render.yml}. On Render, actuator shares
 * the ONE public port; SecurityConfig leaves /actuator/prometheus open because in plain prod it sits
 * on a private port. So exposing anything but health here would publish metrics to the internet.
 */
class RenderProfileTest {

    private static PropertySource<?> render;

    @BeforeAll
    static void load() throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("render", new ClassPathResource("application-render.yml"));
        render = sources.get(0);
    }

    @Test
    void onlyHealthIsExposed_onTheSharedPublicPort() {
        assertThat(render.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(render.getProperty("management.server.port")).isEqualTo(render.getProperty("server.port"));
    }

    @Test
    void listensOnRendersPort_andSendsMailOverHttps() {
        assertThat(render.getProperty("server.port")).isEqualTo("${PORT:8080}");
        assertThat(render.getProperty("mail.transport")).isEqualTo("brevo-api");
    }

    @Test
    void databaseConnectionRequiresTls() {
        assertThat(String.valueOf(render.getProperty("spring.datasource.url"))).endsWith("?sslmode=require");
    }
}
