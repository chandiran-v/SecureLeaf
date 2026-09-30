package com.secureleaf.ratelimit;

import com.secureleaf.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 11, criterion 4 — the public preview limit is keyed by client IP, and a spoofed
 * X-Forwarded-For from an untrusted peer buys the attacker nothing. The product does not exist, so
 * each allowed request is a 404: the limiter runs before the controller either way, which is the point.
 */
class RateLimitIT extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private static RequestPostProcessor from(String remoteAddr) {
        return req -> {
            req.setRemoteAddr(remoteAddr);
            return req;
        };
    }

    @Test
    void previewIsLimitedPerIp_andRejectionCarriesRetryAfter() throws Exception {
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/products/999/preview/1").with(from("203.0.113.10")))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/products/999/preview/1").with(from("203.0.113.10")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // A different client IP has its own bucket.
        mockMvc.perform(get("/api/products/999/preview/1").with(from("203.0.113.11")))
                .andExpect(status().isNotFound());
    }

    @Test
    void spoofedForwardedForFromAnUntrustedPeerDoesNotBuyAFreshBucket() throws Exception {
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/products/999/preview/1").with(from("203.0.113.20"))
                            .header("X-Forwarded-For", "9.9.9." + i))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/products/999/preview/1").with(from("203.0.113.20"))
                        .header("X-Forwarded-For", "9.9.9.200"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void forwardedForIsHonouredFromATrustedProxy() throws Exception {
        // 127.0.0.1 is in the default ratelimit.trusted-proxies, so each XFF value is its own client.
        for (int i = 0; i < 25; i++) {
            mockMvc.perform(get("/api/products/999/preview/1").with(from("127.0.0.1"))
                            .header("X-Forwarded-For", "198.51.100." + i))
                    .andExpect(status().isNotFound());
        }
    }
}
