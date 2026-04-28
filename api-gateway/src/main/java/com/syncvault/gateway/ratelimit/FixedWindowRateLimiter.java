package com.syncvault.gateway.ratelimit;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class FixedWindowRateLimiter {

    private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

    /**
     * Returns true if the request is within the rate limit for the current 1-minute window.
     * Thread-safe: uses ConcurrentHashMap.compute for atomic read-modify-write.
     */
    public boolean tryAcquire(String key, int limit) {
        long now = System.currentTimeMillis();
        long windowStart = (now / 60_000L) * 60_000L;

        long[] result = windows.compute(key, (k, existing) -> {
            if (existing == null || existing[0] < windowStart) {
                return new long[]{windowStart, 1L};
            }
            existing[1]++;
            return existing;
        });

        return result[1] <= limit;
    }
}
