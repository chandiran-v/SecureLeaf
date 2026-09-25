package com.secureleaf.viewer.security;

import com.secureleaf.viewer.DrmProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests — no Spring context, no database, no Redis. TileUrlSigner's only
 * dependencies are {@link DrmProperties} and an injectable {@link Clock}, exactly so this
 * class can fix "now" instead of racing the real clock (acceptance criterion 13).
 */
class TileUrlSignerTest {

    private static final long SESSION_ID = 42L;
    private static final int PAGE_NUMBER = 3;
    private static final long USER_ID = 7L;

    private Instant now;
    private Clock clock;
    private TileUrlSigner signer;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-01-01T00:00:00Z");
        clock = Clock.fixed(now, ZoneOffset.UTC);
        DrmProperties props = new DrmProperties("test-signing-secret-32-bytes-min!", 30, new DrmProperties.Session(15, 45));
        signer = new TileUrlSigner(props, clock);
    }

    @Test
    void signThenVerify_roundTrips() {
        TileUrlSigner.Signature sig = signer.sign(SESSION_ID, PAGE_NUMBER, USER_ID);

        assertThat(sig.expiresAtEpochSeconds()).isEqualTo(now.getEpochSecond() + 30);
        assertThat(signer.verify(SESSION_ID, PAGE_NUMBER, USER_ID, sig.expiresAtEpochSeconds(), sig.value())).isTrue();
    }

    @Test
    void expiredSignature_failsVerification() {
        TileUrlSigner.Signature sig = signer.sign(SESSION_ID, PAGE_NUMBER, USER_ID);

        // Advance a new signer's clock past the exp this signature was bound to.
        Clock later = Clock.fixed(now.plusSeconds(31), ZoneOffset.UTC);
        TileUrlSigner expiredView = new TileUrlSigner(
                new DrmProperties("test-signing-secret-32-bytes-min!", 30, new DrmProperties.Session(15, 45)), later);

        assertThat(expiredView.verify(SESSION_ID, PAGE_NUMBER, USER_ID, sig.expiresAtEpochSeconds(), sig.value())).isFalse();
    }

    @Test
    void exactlyAtExpiry_stillValid() {
        TileUrlSigner.Signature sig = signer.sign(SESSION_ID, PAGE_NUMBER, USER_ID);
        Clock atExpiry = Clock.fixed(Instant.ofEpochSecond(sig.expiresAtEpochSeconds()), ZoneOffset.UTC);
        TileUrlSigner atExpiryView = new TileUrlSigner(
                new DrmProperties("test-signing-secret-32-bytes-min!", 30, new DrmProperties.Session(15, 45)), atExpiry);

        assertThat(atExpiryView.verify(SESSION_ID, PAGE_NUMBER, USER_ID, sig.expiresAtEpochSeconds(), sig.value())).isTrue();
    }

    @Test
    void tamperedSessionId_failsVerification() {
        TileUrlSigner.Signature sig = signer.sign(SESSION_ID, PAGE_NUMBER, USER_ID);
        assertThat(signer.verify(SESSION_ID + 1, PAGE_NUMBER, USER_ID, sig.expiresAtEpochSeconds(), sig.value())).isFalse();
    }

    @Test
    void tamperedPageNumber_failsVerification() {
        TileUrlSigner.Signature sig = signer.sign(SESSION_ID, PAGE_NUMBER, USER_ID);
        assertThat(signer.verify(SESSION_ID, PAGE_NUMBER + 1, USER_ID, sig.expiresAtEpochSeconds(), sig.value())).isFalse();
    }

    @Test
    void tamperedUserId_failsVerification() {
        TileUrlSigner.Signature sig = signer.sign(SESSION_ID, PAGE_NUMBER, USER_ID);
        assertThat(signer.verify(SESSION_ID, PAGE_NUMBER, USER_ID + 1, sig.expiresAtEpochSeconds(), sig.value())).isFalse();
    }

    @Test
    void tamperedExp_failsVerification() {
        TileUrlSigner.Signature sig = signer.sign(SESSION_ID, PAGE_NUMBER, USER_ID);
        assertThat(signer.verify(SESSION_ID, PAGE_NUMBER, USER_ID, sig.expiresAtEpochSeconds() + 1, sig.value())).isFalse();
    }

    @Test
    void tamperedSignatureValue_failsVerification() {
        TileUrlSigner.Signature sig = signer.sign(SESSION_ID, PAGE_NUMBER, USER_ID);
        String tampered = sig.value().equals("AAAA") ? "BBBB" : "AAAA";
        assertThat(signer.verify(SESSION_ID, PAGE_NUMBER, USER_ID, sig.expiresAtEpochSeconds(), tampered)).isFalse();
    }

    /**
     * verify() uses MessageDigest.isEqual (constant-time) rather than String.equals, precisely
     * so a mismatched signature of a DIFFERENT length behaves the same as one of the SAME
     * length — no early exit, no exception, just false. This exercises that code path.
     */
    @Test
    void differentLengthSignature_failsSafelyWithoutException() {
        TileUrlSigner.Signature sig = signer.sign(SESSION_ID, PAGE_NUMBER, USER_ID);
        assertThat(signer.verify(SESSION_ID, PAGE_NUMBER, USER_ID, sig.expiresAtEpochSeconds(), "short")).isFalse();
    }

    @Test
    void nullSignature_failsSafely() {
        assertThat(signer.verify(SESSION_ID, PAGE_NUMBER, USER_ID, now.getEpochSecond() + 30, null)).isFalse();
    }

    @Test
    void differentSigningSecret_failsVerification() {
        TileUrlSigner.Signature sig = signer.sign(SESSION_ID, PAGE_NUMBER, USER_ID);
        TileUrlSigner otherSecret = new TileUrlSigner(
                new DrmProperties("a-completely-different-secret!!!", 30, new DrmProperties.Session(15, 45)), clock);

        assertThat(otherSecret.verify(SESSION_ID, PAGE_NUMBER, USER_ID, sig.expiresAtEpochSeconds(), sig.value())).isFalse();
    }
}
