package com.secureleaf.ratelimit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Phase 11, D4 — who is really calling?
 *
 * {@code X-Forwarded-For} is just a header: anyone can send {@code X-Forwarded-For: 1.2.3.4} and,
 * if we believed it blindly, get a fresh preview bucket per request by inventing a new "IP" each
 * time. So the header is honoured ONLY when the TCP peer ({@code remoteAddr}) is one of our own
 * reverse proxies ({@code ratelimit.trusted-proxies}). A proxy appends the address it saw to the
 * right-hand end of the list, so we walk the list right-to-left and stop at the first address that
 * is not itself a trusted proxy: everything to the left of that was typed by the client and is ignored.
 */
@Component
public class ClientIpResolver {

    /** Request attribute holding the raw X-Forwarded-For value, captured before Spring strips it. */
    static final String RAW_XFF_ATTRIBUTE = ClientIpResolver.class.getName() + ".XFF";

    private final List<IpAddressMatcher> trusted;

    public ClientIpResolver(RateLimitProperties properties) {
        List<String> cidrs = properties.trustedProxies() == null ? List.of() : properties.trustedProxies();
        this.trusted = cidrs.stream().map(String::trim).filter(c -> !c.isEmpty()).map(IpAddressMatcher::new).toList();
    }

    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isTrusted(remoteAddr)) {
            return remoteAddr;
        }
        String xff = rawForwardedFor(request);
        if (xff == null || xff.isBlank()) {
            return remoteAddr;
        }
        String[] hops = xff.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (hop.isEmpty()) {
                continue;
            }
            if (!isTrusted(hop)) {
                return hop;
            }
        }
        return remoteAddr;
    }

    private boolean isTrusted(String address) {
        if (address == null) {
            return false;
        }
        for (IpAddressMatcher matcher : trusted) {
            try {
                if (matcher.matches(address)) {
                    return true;
                }
            } catch (IllegalArgumentException notAnIp) {
                return false; // a garbage hop is never a trusted proxy
            }
        }
        return false;
    }

    private static String rawForwardedFor(HttpServletRequest request) {
        Object captured = request.getAttribute(RAW_XFF_ATTRIBUTE);
        return captured instanceof String s ? s : request.getHeader("X-Forwarded-For");
    }
}
