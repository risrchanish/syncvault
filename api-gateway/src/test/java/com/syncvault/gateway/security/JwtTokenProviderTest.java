package com.syncvault.gateway.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "test-jwt-secret-key-for-tests-only";
    private JwtTokenProvider provider;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(SECRET);
        secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validToken_returnsTrue() {
        String token = buildToken(UUID.randomUUID().toString(), 3_600_000);
        assertThat(provider.validateToken(token)).isTrue();
    }

    @Test
    void expiredToken_returnsFalse() {
        String token = buildToken(UUID.randomUUID().toString(), -1_000);
        assertThat(provider.validateToken(token)).isFalse();
    }

    @Test
    void malformedToken_returnsFalse() {
        assertThat(provider.validateToken("not.a.valid.token")).isFalse();
    }

    @Test
    void emptyToken_returnsFalse() {
        assertThat(provider.validateToken("")).isFalse();
    }

    @Test
    void extractUserId_validToken_returnsSubject() {
        String userId = UUID.randomUUID().toString();
        String token = buildToken(userId, 3_600_000);
        assertThat(provider.extractUserId(token)).isEqualTo(userId);
    }

    @Test
    void extractUserId_expiredToken_throws() {
        String token = buildToken(UUID.randomUUID().toString(), -1_000);
        assertThatThrownBy(() -> provider.extractUserId(token))
                .isInstanceOf(Exception.class);
    }

    private String buildToken(String userId, long expirationOffsetMs) {
        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationOffsetMs))
                .signWith(secretKey)
                .compact();
    }
}
