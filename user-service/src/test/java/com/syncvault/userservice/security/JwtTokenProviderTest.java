package com.syncvault.userservice.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    // 32-char key = 256 bits — minimum for HMAC-SHA256
    private static final String SECRET = "test-jwt-secret-key-for-tests-only";
    private static final long EXPIRY_MS = 900_000L; // 15 minutes

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET, EXPIRY_MS);
    }

    // ---------------------------------------------------------------
    // token generation
    // ---------------------------------------------------------------

    @Test
    void generateAccessToken_containsCorrectUserId_asSubjectClaim() {
        String token = provider.generateAccessToken("user-abc");
        assertThat(provider.extractUserId(token)).isEqualTo("user-abc");
    }

    @Test
    void generateAccessToken_producesValidToken_withinExpiry() {
        String token = provider.generateAccessToken("user-xyz");
        assertThat(provider.isTokenValid(token)).isTrue();
    }

    @Test
    void generateAccessToken_correctExpiry_tokenExpiredAfterWindow() {
        // Negative expiry → expiration is set in the past; token is expired immediately
        JwtTokenProvider expiredProvider = new JwtTokenProvider(SECRET, -1L);
        String token = expiredProvider.generateAccessToken("user-short");
        assertThat(provider.isTokenValid(token)).isFalse();
    }

    @Test
    void generateAccessToken_differentUsers_produceDifferentTokens() {
        String t1 = provider.generateAccessToken("user-1");
        String t2 = provider.generateAccessToken("user-2");
        assertThat(t1).isNotEqualTo(t2);
    }

    // ---------------------------------------------------------------
    // token validation
    // ---------------------------------------------------------------

    @Test
    void isTokenValid_validToken_returnsTrue() {
        String token = provider.generateAccessToken("user-valid");
        assertThat(provider.isTokenValid(token)).isTrue();
    }

    @Test
    void isTokenValid_expiredToken_returnsFalse() {
        // Negative expiry → expiration set 1 hour in the past
        JwtTokenProvider expiredProvider = new JwtTokenProvider(SECRET, -3_600_000L);
        String expiredToken = expiredProvider.generateAccessToken("user-expired");

        assertThat(provider.isTokenValid(expiredToken)).isFalse();
    }

    @Test
    void isTokenValid_tamperedSignature_returnsFalse() {
        String token = provider.generateAccessToken("user-tamper");
        String[] parts = token.split("\\.");
        // Flip one character in the signature segment
        String sig = parts[2];
        char flipped = (sig.charAt(0) == 'A') ? 'B' : 'A';
        parts[2] = flipped + sig.substring(1);
        String tampered = String.join(".", parts);

        assertThat(provider.isTokenValid(tampered)).isFalse();
    }

    @Test
    void isTokenValid_tamperedPayload_returnsFalse() {
        String token = provider.generateAccessToken("user-tamper2");
        String[] parts = token.split("\\.");
        // Corrupt the payload segment
        parts[1] = parts[1].length() > 1
                ? parts[1].substring(1) + "X"
                : "corrupt";
        String tampered = String.join(".", parts);

        assertThat(provider.isTokenValid(tampered)).isFalse();
    }

    @Test
    void isTokenValid_malformedToken_dotSeparated_returnsFalse() {
        assertThat(provider.isTokenValid("not.a.jwt")).isFalse();
    }

    @Test
    void isTokenValid_malformedToken_noSegments_returnsFalse() {
        assertThat(provider.isTokenValid("completelymalformed")).isFalse();
    }

    @Test
    void isTokenValid_emptyString_returnsFalse() {
        assertThat(provider.isTokenValid("")).isFalse();
    }

    @Test
    void isTokenValid_tokenSignedWithDifferentKey_returnsFalse() {
        JwtTokenProvider otherProvider = new JwtTokenProvider("DifferentSecretKey789012345678901", EXPIRY_MS);
        String tokenFromOtherKey = otherProvider.generateAccessToken("user-other");

        assertThat(provider.isTokenValid(tokenFromOtherKey)).isFalse();
    }

    // ---------------------------------------------------------------
    // extractUserId
    // ---------------------------------------------------------------

    @Test
    void extractUserId_validToken_returnsExactUserId() {
        String userId = "550e8400-e29b-41d4-a716-446655440000";
        String token = provider.generateAccessToken(userId);
        assertThat(provider.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    void extractUserId_roundTrip_consistentAcrossMultipleCalls() {
        String userId = "test-user-id-123";
        String token = provider.generateAccessToken(userId);
        assertThat(provider.extractUserId(token)).isEqualTo(userId);
        assertThat(provider.extractUserId(token)).isEqualTo(userId); // idempotent
    }
}
