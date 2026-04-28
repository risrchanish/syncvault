package com.syncvault.gateway;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("test")
class GatewayIntegrationTest {

    private static final String SECRET = "test-jwt-secret-key-for-tests-only";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void protectedPath_noToken_returns401() {
        webTestClient.get().uri("/files/test")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedPath_invalidToken_returns401() {
        webTestClient.get().uri("/users/profile")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid.jwt.token")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void protectedPath_expiredToken_returns401() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(new Date(System.currentTimeMillis() - 10_000))
                .expiration(new Date(System.currentTimeMillis() - 1_000))
                .signWith(key)
                .compact();

        webTestClient.get().uri("/files/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void publicPath_noToken_notRejectedByJwtFilter() {
        // Gateway will try to forward to downstream (which isn't running in tests)
        // but must NOT return 401 — the JWT filter must pass through public paths.
        // We expect anything except 401.
        webTestClient.post().uri("/auth/login")
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .bodyValue("{\"email\":\"x@x.com\",\"password\":\"pass\"}")
                .exchange()
                .expectStatus().value(status ->
                        org.assertj.core.api.Assertions.assertThat(status)
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }

    @Test
    void validToken_protectedPath_notRejectedByJwtFilter() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key)
                .compact();

        // Gateway forwards to downstream (not running) — expect anything except 401
        webTestClient.get().uri("/files/test")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().value(status ->
                        org.assertj.core.api.Assertions.assertThat(status)
                                .isNotEqualTo(HttpStatus.UNAUTHORIZED.value()));
    }
}
