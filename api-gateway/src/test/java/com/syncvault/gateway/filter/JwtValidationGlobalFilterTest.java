package com.syncvault.gateway.filter;

import com.syncvault.gateway.security.JwtTokenProvider;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtValidationGlobalFilterTest {

    private static final String SECRET = "test-jwt-secret-key-for-tests-only";

    @Mock
    private GatewayFilterChain chain;

    private JwtTokenProvider jwtTokenProvider;
    private JwtValidationGlobalFilter filter;
    private SecretKey secretKey;

    @BeforeEach
    void setUp() {
        secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        jwtTokenProvider = new JwtTokenProvider(SECRET);
        filter = new JwtValidationGlobalFilter(jwtTokenProvider, List.of("/auth/**", "/actuator/**"));
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void publicPath_auth_chainCalledWithoutValidation() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/auth/login").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void publicPath_actuator_chainCalled() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }

    @Test
    void protectedPath_noToken_returns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/files/test").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void protectedPath_noAuthorizationHeader_returns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/users/profile").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedPath_malformedBearer_returns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/files/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.valid.jwt")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(chain, never()).filter(any());
    }

    @Test
    void protectedPath_expiredToken_returns401() {
        String expired = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .issuedAt(new Date(System.currentTimeMillis() - 10_000))
                .expiration(new Date(System.currentTimeMillis() - 1_000))
                .signWith(secretKey)
                .compact();

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/files/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protectedPath_validToken_chainCalledWithXUserId() {
        String userId = UUID.randomUUID().toString();
        String token = buildValidToken(userId);

        AtomicReference<String> capturedUserId = new AtomicReference<>();
        when(chain.filter(any())).thenAnswer(inv -> {
            ServerWebExchange ex = inv.getArgument(0);
            capturedUserId.set(ex.getRequest().getHeaders().getFirst("X-User-Id"));
            return Mono.empty();
        });

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/files/test")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
        assertThat(capturedUserId.get()).isEqualTo(userId);
    }

    @Test
    void filterOrder_isHighestPrecedence() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    private String buildValidToken(String userId) {
        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(secretKey)
                .compact();
    }
}
