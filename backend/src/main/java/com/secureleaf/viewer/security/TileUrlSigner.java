package com.secureleaf.viewer.security;

import com.secureleaf.viewer.DrmProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;

/**
 * Signs and verifies D5's tile URLs: {@code /api/viewer/tiles/{sessionId}/{pageNumber}
 * ?exp={epochSeconds}&sig={base64url}}.
 *
 * WHY AN HMAC AND NOT A PRESIGNED MINIO URL
 * MinIO can already mint presigned GET URLs (see {@link com.secureleaf.common.storage.StorageService
 * #presignedGetUrl}) — but those point straight at the CLEAN object. The whole point of D6/D7 is
 * that no clean tile ever reaches the browser; every response must pass through the watermark
 * renderer first. So the "presigned URL" here doesn't point at storage at all — it's a signed,
 * time-boxed, single-use *permission slip* for our own {@code SecureTileController}, which
 * fetches, watermarks, and logs on every hit. See the learning note for the full comparison.
 *
 * Pure and stateless — no repository, no Redis — so it's unit-testable with a fixed {@link Clock}
 * and no Spring context (see TileUrlSignerTest).
 */
@Component
@RequiredArgsConstructor
public class TileUrlSigner {

    private static final String ALGORITHM = "HmacSHA256";

    private final DrmProperties drmProperties;
    private final Clock clock;

    /** A freshly minted signature: the exp it was bound to, and the base64url signature itself. */
    public record Signature(long expiresAtEpochSeconds, String value) {
    }

    /** Mints a signature valid for {@code drm.signed-url-ttl-seconds} from now (D5). */
    public Signature sign(long sessionId, int pageNumber, long userId) {
        long exp = clock.instant().getEpochSecond() + drmProperties.signedUrlTtlSeconds();
        return new Signature(exp, hmac(sessionId, pageNumber, userId, exp));
    }

    /**
     * Verifies a signature against the exact same fields it must have been signed with — anyone
     * with the secret and the four fields can recompute {@code expected}, so tampering with
     * {@code sessionId}, {@code pageNumber}, {@code userId} (i.e. presenting someone else's URL
     * as your own) or {@code exp} all produce a mismatch here, not just an expired-clock failure.
     */
    public boolean verify(long sessionId, int pageNumber, long userId, long exp, String providedSignature) {
        if (providedSignature == null) return false;
        if (clock.instant().getEpochSecond() > exp) return false;
        String expected = hmac(sessionId, pageNumber, userId, exp);
        // Constant-time compare (MessageDigest.isEqual), same reasoning as RazorpaySignatures.matches:
        // String.equals short-circuits on the first differing byte, which leaks timing information
        // an attacker could use to guess a valid signature one byte at a time.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedSignature.getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(long sessionId, int pageNumber, long userId, long exp) {
        String payload = sessionId + "|" + pageNumber + "|" + userId + "|" + exp;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(drmProperties.signingSecret().getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
