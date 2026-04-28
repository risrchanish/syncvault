package com.syncvault.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowRateLimiterTest {

    private FixedWindowRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new FixedWindowRateLimiter();
    }

    @Test
    void firstRequest_isAllowed() {
        assertThat(rateLimiter.tryAcquire("key-1", 5)).isTrue();
    }

    @Test
    void requestsUpToLimit_areAllowed() {
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.tryAcquire("key-limit", 5)).isTrue();
        }
    }

    @Test
    void requestExceedingLimit_isDenied() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryAcquire("key-exceed", 5);
        }
        assertThat(rateLimiter.tryAcquire("key-exceed", 5)).isFalse();
    }

    @Test
    void differentKeys_haveIndependentLimits() {
        for (int i = 0; i < 5; i++) {
            rateLimiter.tryAcquire("key-a", 5);
        }
        // key-a is exhausted, key-b should still pass
        assertThat(rateLimiter.tryAcquire("key-a", 5)).isFalse();
        assertThat(rateLimiter.tryAcquire("key-b", 5)).isTrue();
    }

    @Test
    void limitOfOne_secondRequestDenied() {
        assertThat(rateLimiter.tryAcquire("key-one", 1)).isTrue();
        assertThat(rateLimiter.tryAcquire("key-one", 1)).isFalse();
    }
}
