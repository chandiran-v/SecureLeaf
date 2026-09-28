package com.secureleaf.common;

import com.secureleaf.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 9, D3 — every response carries the security headers from SecurityConfig's
 * {@code .headers(...)} block. Acceptance criterion 2. Uses {@code /actuator/health} (public, D7)
 * as the probe endpoint — any response works since these headers are chain-wide, not
 * endpoint-specific.
 */
class SecurityHeadersIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void everyResponse_carriesTheHardeningHeaders() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
                .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
                .andExpect(header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void hstsHeader_present_onlyOnSecureRequests() throws Exception {
        MockHttpServletRequestBuilder secureRequest = get("/actuator/health");
        secureRequest.secure(true);

        mockMvc.perform(secureRequest)
                .andExpect(status().isOk())
                .andExpect(header().exists("Strict-Transport-Security"));

        // Plain HTTP (what MockMvc sends by default): Spring Security's HSTS writer never adds
        // the header at all for an insecure request — sending it over HTTP would be misleading
        // (HSTS only makes sense as an instruction a browser received over a genuine HTTPS
        // connection) and is explicitly against the header's own spec (RFC 6797 §7.2).
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Strict-Transport-Security"));
    }
}
