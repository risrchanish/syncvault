package com.syncvault.gateway.filter;

import com.syncvault.gateway.security.JwtTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Slf4j
@Component
public class JwtValidationGlobalFilter implements GlobalFilter, Ordered {

    private final JwtTokenProvider jwtTokenProvider;
    private final List<String> publicPaths;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public JwtValidationGlobalFilter(
            JwtTokenProvider jwtTokenProvider,
            @Value("${gateway.public-paths}") List<String> publicPaths) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.publicPaths = publicPaths;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isPublicPath(path)) {
            log.debug("Public path [{}] — skipping JWT check", path);
            return chain.filter(exchange);
        }

        String token = extractBearerToken(exchange);
        if (token == null) {
            log.debug("No Bearer token for protected path [{}] — returning 401", path);
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }

        try {
            String userId = jwtTokenProvider.extractUserId(token);
            log.debug("JWT valid for path [{}] — userId: [{}]", path, userId);
            ServerWebExchange mutated = exchange.mutate()
                    .request(r -> r.header("X-User-Id", userId))
                    .build();
            return chain.filter(mutated);
        } catch (Exception e) {
            log.debug("JWT validation failed for path [{}]: {}", path, e.getMessage());
            return reject(exchange, HttpStatus.UNAUTHORIZED);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private boolean isPublicPath(String path) {
        return publicPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private String extractBearerToken(ServerWebExchange exchange) {
        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status) {
        exchange.getResponse().setStatusCode(status);
        return exchange.getResponse().setComplete();
    }
}
