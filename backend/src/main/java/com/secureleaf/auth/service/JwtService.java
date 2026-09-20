package com.secureleaf.auth.service;

import com.secureleaf.auth.config.JwtProperties;
import com.secureleaf.auth.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Core JWT utility — generates and validates access tokens, produces opaque
 * refresh tokens, and provides a SHA-256 hashing helper for token storage.
 *
 * <p><b>Design</b>: Access tokens are short-lived JWTs carrying userId, email, and roles.
 * Refresh tokens are random UUIDs stored as SHA-256 hashes in the database,
 * enabling server-side revocation (impossible with stateless JWTs).</p>
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties jwtProperties;
    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        byte[] keyBytes = jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8);
        this.signingKey = new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    // ── Access Token ────────────────────────────────────────────────────────────

    public String generateAccessToken(User user) {
        List<String> roles = user.getRoles().stream()
                .map(ur -> ur.getRole().name())
                .toList();

        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("email", user.getEmail())
                .claim("roles", roles)
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusMillis(jwtProperties.getAccessTokenExpiryMs())))
                .signWith(signingKey)
                .compact();
    }

    public Claims validateAccessToken(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long extractUserId(String token) {
        return Long.parseLong(validateAccessToken(token).getSubject());
    }

    // ── Refresh Token ───────────────────────────────────────────────────────────

    /** Generates a cryptographically random opaque refresh token. */
    public String generateRefreshToken() {
        return UUID.randomUUID().toString();
    }

    public long getRefreshTokenExpiryMs() {
        return jwtProperties.getRefreshTokenExpiryMs();
    }

    // ── Hashing ─────────────────────────────────────────────────────────────────

    /** SHA-256 hash used to store refresh tokens and compare on lookup. */
    public String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
