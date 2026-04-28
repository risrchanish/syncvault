package com.syncvault.gateway.filter;

import com.syncvault.gateway.ratelimit.FixedWindowRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RateLimitingGlobalFilterTest {

    private FixedWindowRateLimiter rateLimiter;
    private RateLimitingGlobalFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        rateLimiter = new FixedWindowRateLimiter();
        // low limits so tests can exhaust them quickly
        filter = new RateLimitingGlobalFilter(rateLimiter, 3, 2);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void authenticatedUser_withinLimit_isAllowed() {
        MockServerWebExchange exchange = buildExchangeWithUser("user-ok");

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void authenticatedUser_exceedsLimit_returns429() {
        String userId = "user-limited";
        for (int i = 0; i < 3; i++) {
            filter.filter(buildExchangeWithUser(userId), chain).block();
        }
        MockServerWebExchange last = buildExchangeWithUser(userId);

        StepVerifier.create(filter.filter(last, chain)).verifyComplete();

        assertThat(last.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verify(chain, times(3)).filter(any());
    }

    @Test
    void unauthenticatedUser_withinLimit_isAllowed() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/auth/login").build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }

    @Test
    void unauthenticatedUser_exceedsLimit_returns429() {
        // exhaust unauthenticated limit (2) — all keyed as "ip:unknown" since no remote addr in mock
        for (int i = 0; i < 2; i++) {
            filter.filter(MockServerWebExchange.from(
                    MockServerHttpRequest.post("/auth/login").build()), chain).block();
        }
        MockServerWebExchange last = MockServerWebExchange.from(
                MockServerHttpRequest.post("/auth/login").build());

        StepVerifier.create(filter.filter(last, chain)).verifyComplete();

        assertThat(last.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void xForwardedFor_usedAsIpKey() {
        // first request with a specific forwarded IP is allowed
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/auth/register")
                        .header("X-Forwarded-For", "203.0.113.5, 10.0.0.1")
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }

    @Test
    void remoteAddress_usedWhenNoForwardedHeader() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/auth/login")
                        .remoteAddress(new InetSocketAddress("10.0.0.42", 54321))
                        .build());

        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        verify(chain).filter(any());
    }

    @Test
    void filterOrder_isHighestPrecedencePlusOne() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
    }

    @Test
    void getClientIp_xForwardedFor_returnsFirstIp() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .header("X-Forwarded-For", "1.2.3.4, 5.6.7.8")
                        .build());

        String ip = filter.getClientIp(exchange);

        assertThat(ip).isEqualTo("1.2.3.4");
    }

    @Test
    void getClientIp_noForwardedHeader_returnsRemoteAddress() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test")
                        .remoteAddress(new InetSocketAddress("192.168.1.100", 9000))
                        .build());

        String ip = filter.getClientIp(exchange);

        assertThat(ip).isEqualTo("192.168.1.100");
    }

    @Test
    void getClientIp_noHeaderNoRemoteAddr_returnsUnknown() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/test").build());

        String ip = filter.getClientIp(exchange);

        assertThat(ip).isEqualTo("unknown");
    }

    private MockServerWebExchange buildExchangeWithUser(String userId) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("/files/test")
                        .header("X-User-Id", userId)
                        .build());
    }
}
