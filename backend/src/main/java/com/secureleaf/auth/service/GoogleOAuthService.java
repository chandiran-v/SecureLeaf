package com.secureleaf.auth.service;

import com.secureleaf.common.exception.BusinessException;
import com.secureleaf.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

/**
 * Verifies Google OAuth2 ID tokens by checking their signature against
 * Google's public JWKS and validating issuer + audience claims.
 *
 * <p>Requires {@code google.oauth2.client-id} to be set in configuration.
 * When not configured, calls to {@link #verifyIdToken} throw a descriptive error.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GoogleOAuthService {

    @Value("${google.oauth2.client-id:}")
    private String googleClientId;

    private final NimbusJwtDecoder googleJwtDecoder;

    /**
     * Verifies a Google ID token and extracts user information.
     *
     * @param idToken the raw ID token string from the frontend
     * @return parsed Google user information
     * @throws BusinessException if verification fails or OAuth2 is not configured
     */
    public GoogleUserInfo verifyIdToken(String idToken) {
        if (googleClientId == null || googleClientId.isBlank()) {
            throw new BusinessException(ErrorCode.OAUTH_NOT_CONFIGURED,
                    "Google OAuth2 is not configured. Set GOOGLE_CLIENT_ID environment variable.");
        }

        try {
            Jwt jwt = googleJwtDecoder.decode(idToken);

            // Validate audience
            if (jwt.getAudience() == null || !jwt.getAudience().contains(googleClientId)) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN,
                        "Google ID token audience mismatch. Expected: " + googleClientId);
            }

            // Check email_verified
            Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");
            if (emailVerified == null || !emailVerified) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN, "Google email is not verified");
            }

            return new GoogleUserInfo(
                    jwt.getClaimAsString("sub"),
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("name"),
                    jwt.getClaimAsString("picture")
            );

        } catch (JwtException e) {
            log.warn("Failed to verify Google ID token: {}", e.getMessage());
            throw new BusinessException(ErrorCode.INVALID_TOKEN,
                    "Failed to verify Google ID token: " + e.getMessage(), e);
        }
    }

    /**
     * Extracted user information from a verified Google ID token.
     */
    public record GoogleUserInfo(String sub, String email, String name, String picture) {}
}
