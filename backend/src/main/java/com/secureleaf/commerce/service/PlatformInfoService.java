package com.secureleaf.commerce.service;

import com.secureleaf.commerce.dto.PlatformInfoDto;
import com.secureleaf.commerce.gateway.PaymentGateway;
import com.secureleaf.commerce.gateway.PaymentGatewayProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * D8 — tells the UI whether real money can move. The mode is derived, never configured directly:
 * <ul>
 *   <li>mock provider → {@code MOCK}</li>
 *   <li>razorpay + {@code rzp_live_…} key → {@code LIVE}</li>
 *   <li>razorpay + anything else (i.e. {@code rzp_test_…}) → {@code TEST}</li>
 * </ul>
 * Deriving it from the key itself means the banner can't disagree with what the server will actually do.
 */
@Service
public class PlatformInfoService {

    private final PaymentGateway paymentGateway;
    private final PaymentGatewayProperties gatewayProperties;
    private final String supportEmail;

    public PlatformInfoService(PaymentGateway paymentGateway, PaymentGatewayProperties gatewayProperties,
                               @Value("${app.support-email:support@secureleaf.local}") String supportEmail) {
        this.paymentGateway = paymentGateway;
        this.gatewayProperties = gatewayProperties;
        this.supportEmail = supportEmail;
    }

    public PlatformInfoDto platformInfo() {
        return new PlatformInfoDto(paymentMode(paymentGateway.provider(), gatewayProperties.keyId()), supportEmail);
    }

    static String paymentMode(String provider, String keyId) {
        if (!"RAZORPAY".equals(provider)) return "MOCK";
        return keyId != null && keyId.startsWith("rzp_live_") ? "LIVE" : "TEST";
    }
}
