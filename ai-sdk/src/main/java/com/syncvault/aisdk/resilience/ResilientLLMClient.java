package com.syncvault.aisdk.resilience;

import com.syncvault.aisdk.client.LLMClient;
import com.syncvault.aisdk.exception.LLMProviderException;
import com.syncvault.aisdk.model.EmbeddingResponse;
import com.syncvault.aisdk.model.LLMRequest;
import com.syncvault.aisdk.model.LLMResponse;
import com.syncvault.aisdk.exception.TokenLimitExceededException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Decorator that adds Resilience4j retry and circuit-breaker protection around any
 * {@link LLMClient}, keeping all resilience concerns out of the provider implementations.
 *
 * <h2>Nesting order — Circuit Breaker outer, Retry inner</h2>
 * <p>The execution stack for every network call is:
 * <pre>
 *   CircuitBreaker.decorate( Retry.decorate( providerCall ) )
 * </pre>
 * With Retry on the inside, each retry attempt is invisible to the circuit breaker —
 * the CB only sees the <em>final outcome</em> of the full retry sequence. This is
 * intentional: transient blips (a single 429) are retried silently and never counted
 * as failures in the CB's sliding window. The CB trips only when an entire retry
 * sequence is exhausted, meaning the provider is persistently unhealthy. Reversing
 * the order (Retry outer, CB inner) would cause every individual retry attempt to
 * increment the CB failure counter, tripping it prematurely on normal rate-limit
 * bursts.
 *
 * <h2>Retry configuration</h2>
 * <ul>
 *   <li>Exponential backoff: 1 s → 2 s → 4 s, capped at 8 s</li>
 *   <li>Only retries {@link com.syncvault.aisdk.exception.LLMProviderException} where
 *       {@code isRetryable() == true} (HTTP 429 / 5xx). Non-retryable errors
 *       ({@link com.syncvault.aisdk.exception.TokenLimitExceededException}, 4xx auth
 *       failures) propagate immediately without retry.</li>
 * </ul>
 *
 * <h2>Circuit breaker configuration (optional)</h2>
 * <ul>
 *   <li>Opens when ≥ 50% of the last 10 calls fail (minimum 5 calls evaluated)</li>
 *   <li>Stays open for 30 s, then transitions to HALF-OPEN with 3 probe calls</li>
 *   <li>Only retryable exceptions count as failures — same predicate as Retry</li>
 * </ul>
 *
 * <p>{@link #countTokens} and {@link #isWithinLimit} bypass all resilience wrappers
 * because they are local BPE operations with no network call.
 */
public class ResilientLLMClient implements LLMClient {

    private final LLMClient delegate;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker; // null when circuit breaker is disabled
    private final int tokenLimit;

    // -------------------------------------------------------------------------
    // Public production constructors
    // -------------------------------------------------------------------------

    public ResilientLLMClient(LLMClient delegate, int maxRetries, boolean circuitBreakerEnabled) {
        this(delegate, maxRetries, circuitBreakerEnabled, Integer.MAX_VALUE);
    }

    public ResilientLLMClient(LLMClient delegate, int maxRetries, boolean circuitBreakerEnabled, int tokenLimit) {
        this(delegate,
             defaultRetryConfig(maxRetries),
             circuitBreakerEnabled ? defaultCircuitBreakerConfig() : null,
             tokenLimit);
    }

    // -------------------------------------------------------------------------
    // Package-private constructors for tests (inject fast configs)
    // -------------------------------------------------------------------------

    ResilientLLMClient(LLMClient delegate, RetryConfig retryConfig, CircuitBreakerConfig circuitBreakerConfig) {
        this(delegate, retryConfig, circuitBreakerConfig, Integer.MAX_VALUE);
    }

    ResilientLLMClient(LLMClient delegate, RetryConfig retryConfig, CircuitBreakerConfig circuitBreakerConfig,
                       int tokenLimit) {
        this.delegate       = delegate;
        this.retry          = Retry.of("llm-retry", retryConfig);
        this.circuitBreaker = circuitBreakerConfig != null
                ? CircuitBreaker.of("llm-cb-" + delegate.getProviderName(), circuitBreakerConfig)
                : null;
        this.tokenLimit     = tokenLimit;
    }

    // -------------------------------------------------------------------------
    // Default configs (HLD spec)
    // -------------------------------------------------------------------------

    private static RetryConfig defaultRetryConfig(int maxAttempts) {
        return RetryConfig.custom()
                .maxAttempts(maxAttempts)
                // 1 s → 2 s → 4 s → ... capped at 8 s
                .intervalFunction(IntervalFunction.ofExponentialBackoff(
                        Duration.ofSeconds(1), 2.0, Duration.ofSeconds(8)))
                .retryOnException(ResilientLLMClient::isRetryable)
                .build();
    }

    private static CircuitBreakerConfig defaultCircuitBreakerConfig() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(50)            // OPEN when ≥ 50% of last N calls fail
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)             // need at least 5 calls before evaluating
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .recordException(ResilientLLMClient::isRetryable)
                .build();
    }

    private static boolean isRetryable(Throwable e) {
        return e instanceof LLMProviderException && ((LLMProviderException) e).isRetryable();
    }

    // -------------------------------------------------------------------------
    // LLMClient implementation
    // -------------------------------------------------------------------------

    @Override
    public LLMResponse complete(LLMRequest request) {
        // Token check before retry/CB loop — TokenLimitExceededException propagates immediately (no retry)
        String userMsg = delegate.truncateIfNeeded(request.getUserMessage(), tokenLimit);

        LLMRequest processed = (userMsg == request.getUserMessage()) ? request
                : LLMRequest.builder()
                        .systemPrompt(request.getSystemPrompt())
                        .userMessage(userMsg)
                        .maxTokens(request.getMaxTokens())
                        .temperature(request.getTemperature())
                        .model(request.getModel())
                        .stopSequences(request.getStopSequences())
                        .build();

        return executeWithResilience(() -> delegate.complete(processed));
    }

    @Override
    public LLMResponse completeWithSystem(String systemPrompt, String userMessage) {
        return complete(LLMRequest.builder()
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .build());
    }

    @Override
    public EmbeddingResponse embed(String text) {
        return executeWithResilience(() -> delegate.embed(text));
    }

    /** Token counting is a local BPE operation — no network call, no resilience needed. */
    @Override
    public int countTokens(String text) {
        return delegate.countTokens(text);
    }

    /** Token limit check is a local BPE operation — no network call, no resilience needed. */
    @Override
    public boolean isWithinLimit(String text, int maxTokens) {
        return delegate.isWithinLimit(text, maxTokens);
    }

    /** Appends "-resilient" so callers can identify this as a wrapped client. */
    @Override
    public String getProviderName() {
        return delegate.getProviderName() + "-resilient";
    }

    // -------------------------------------------------------------------------
    // Core resilience execution
    // -------------------------------------------------------------------------

    private <T> T executeWithResilience(Callable<T> operation) {
        Callable<T> decorated = (circuitBreaker != null)
                ? CircuitBreaker.decorateCallable(circuitBreaker, Retry.decorateCallable(retry, operation))
                : Retry.decorateCallable(retry, operation);

        try {
            return decorated.call();
        } catch (CallNotPermittedException e) {
            throw new LLMProviderException(
                    "Circuit breaker is OPEN — provider " + delegate.getProviderName() + " is unavailable", e);
        } catch (TokenLimitExceededException e) {
            throw e;
        } catch (LLMProviderException e) {
            throw e;
        } catch (Exception e) {
            throw new LLMProviderException(
                    "Unexpected error during resilient LLM call to " + delegate.getProviderName(), e);
        }
    }
}
