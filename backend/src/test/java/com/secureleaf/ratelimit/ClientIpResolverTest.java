package com.secureleaf.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 11, D4 / acceptance criterion 4 — X-Forwarded-For is only believed from a trusted proxy. */
class ClientIpResolverTest {

    private final ClientIpResolver resolver = new ClientIpResolver(new RateLimitProperties(
            null, null, null, List.of("10.0.0.0/8", "127.0.0.1/32"), null));

    private static MockHttpServletRequest request(String remoteAddr, String xff) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr(remoteAddr);
        if (xff != null) {
            req.addHeader("X-Forwarded-For", xff);
        }
        return req;
    }

    @Test
    void spoofedHeaderFromAnUntrustedPeerIsIgnored() {
        assertThat(resolver.resolve(request("203.0.113.9", "1.2.3.4"))).isEqualTo("203.0.113.9");
    }

    @Test
    void headerIsHonouredFromATrustedProxy() {
        assertThat(resolver.resolve(request("10.1.2.3", "198.51.100.7"))).isEqualTo("198.51.100.7");
    }

    @Test
    void clientTypedLeftHandEntriesAreIgnored_rightmostUntrustedHopWins() {
        // The client sent "6.6.6.6"; our proxy appended the address it actually saw.
        assertThat(resolver.resolve(request("10.1.2.3", "6.6.6.6, 198.51.100.7, 10.9.9.9"))).isEqualTo("198.51.100.7");
    }

    @Test
    void trustedPeerWithNoHeaderFallsBackToRemoteAddr() {
        assertThat(resolver.resolve(request("10.1.2.3", null))).isEqualTo("10.1.2.3");
    }

    @Test
    void garbageInTheHeaderDoesNotThrow() {
        assertThat(resolver.resolve(request("10.1.2.3", "not-an-ip"))).isEqualTo("not-an-ip");
    }

    @Test
    void noTrustedProxiesConfiguredMeansTheHeaderIsNeverTrusted() {
        ClientIpResolver strict = new ClientIpResolver(new RateLimitProperties(null, null, null, List.of(), null));
        assertThat(strict.resolve(request("10.1.2.3", "1.2.3.4"))).isEqualTo("10.1.2.3");
    }
}
