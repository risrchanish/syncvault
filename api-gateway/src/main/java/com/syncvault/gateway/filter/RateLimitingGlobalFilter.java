package com.syncvault.gateway.filter;

import com.syncvault.gateway.ratelimit.FixedWindowRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

@Slf4j
@Component
public class RateLimitingGlobalFilter implements GlobalFilter, Ordered {

    private final FixedWindowRateLimiter rateLimiter;
    private final int authenticatedLimit;
    private final int unauthenticatedLimit;

    public RateLimitingGlobalFilter(
            FixedWindowRateLimiter rateLimiter,
            @Value("${gateway.rate-limit.authenticated-limit:100}") int authenticatedLimit,
            @Value("${gateway.rate-limit.unauthenticated-limit:10}") int unauthenticatedLimit) {
        this.rateLimiter = rateLimiter;
        this.authenticatedLimit = authenticatedLimit;
        this.unauthenticatedLimit = unauthenticatedLimit;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");

        final String key;
        final int limit;

        if (userId != null) {
            key = "user:" + userId;
            limit = authenticatedLimit;
        } else {
            key = "ip:" + getClientIp(exchange);
            limit = unauthenticatedLimit;
        }

        if (!rateLimiter.tryAcquire(key, limit)) {
            log.debug("Rate limit exceeded for key [{}] (limit={}/min)", key, limit);
            exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }

    String getClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }
}
