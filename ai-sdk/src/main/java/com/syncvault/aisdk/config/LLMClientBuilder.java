package com.syncvault.aisdk.config;

import com.syncvault.aisdk.client.LLMClient;
import com.syncvault.aisdk.provider.ClaudeClient;
import com.syncvault.aisdk.provider.OpenAIClient;
import com.syncvault.aisdk.resilience.ResilientLLMClient;
import com.syncvault.aisdk.token.TokenCounter;

/**
 * Fluent builder for constructing a fully configured {@link LLMClient}.
 *
 * <p>Typical usage:
 * <pre>{@code
 * LLMClient client = LLMClientBuilder.create()
 *     .provider(Provider.OPENAI)
 *     .apiKey(System.getenv("OPENAI_API_KEY"))
 *     .model("gpt-4o-mini")
 *     .tokenLimit(100_000)
 *     .resilient(true)
 *     .maxRetries(3)
 *     .circuitBreaker(true)
 *     .build();
 * }</pre>
 *
 * <p>Switching providers requires changing only {@link #provider} — all call sites
 * that depend on the {@link LLMClient} interface are unaffected.
 *
 * <p>When {@link #resilient(boolean) resilient(true)} is set, the returned client is
 * wrapped by {@link ResilientLLMClient} which adds Resilience4j retry (exponential
 * backoff) and an optional circuit breaker. Without {@code resilient(true)}, the raw
 * provider client is returned with no retry logic.
 */
public class LLMClientBuilder {

    private Provider provider;
    private String apiKey;
    private String model;
    private int maxRetries = 3;
    private boolean circuitBreakerEnabled = false;
    private int tokenLimit = 100_000;
    private boolean resilient = false;

    private LLMClientBuilder() {}

    /**
     * Creates a new, unconfigured builder instance.
     */
    public static LLMClientBuilder create() {
        return new LLMClientBuilder();
    }

    /**
     * Sets the LLM provider (required).
     * Determines which concrete client ({@code OpenAIClient} or {@code ClaudeClient}) is
     * instantiated.
     *
     * @param provider the target provider; must not be {@code null}
     */
    public LLMClientBuilder provider(Provider provider) {
        this.provider = provider;
        return this;
    }

    /**
     * Sets the API key used to authenticate with the provider (required).
     * For OpenAI this is the {@code OPENAI_API_KEY}; for Anthropic, {@code ANTHROPIC_API_KEY}.
     *
     * @param apiKey non-blank API key
     */
    public LLMClientBuilder apiKey(String apiKey) {
        this.apiKey = apiKey;
        return this;
    }

    /**
     * Overrides the default model ID for completions and embeddings.
     * If {@code null}, each provider falls back to its hardcoded default
     * (e.g. {@code gpt-4o-mini} for OpenAI, {@code claude-sonnet-4-6} for Claude).
     *
     * @param model the model identifier string
     */
    public LLMClientBuilder model(String model) {
        this.model = model;
        return this;
    }

    /**
     * Sets the maximum number of retry attempts for transient failures (429, 5xx).
     * Only effective when {@link #resilient(boolean) resilient(true)} is set.
     * Defaults to {@code 3}.
     *
     * @param maxRetries total attempts including the first try (minimum 1)
     */
    public LLMClientBuilder maxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    /**
     * Enables or disables the Resilience4j circuit breaker.
     * Only effective when {@link #resilient(boolean) resilient(true)} is set.
     * When enabled, the circuit breaker opens after ≥ 50% failure rate across the
     * last 10 calls (minimum 5) and stays open for 30 seconds.
     * Defaults to {@code false}.
     *
     * @param enabled {@code true} to wrap with a circuit breaker
     */
    public LLMClientBuilder circuitBreaker(boolean enabled) {
        this.circuitBreakerEnabled = enabled;
        return this;
    }

    /**
     * Sets the per-request token budget enforced by
     * {@link com.syncvault.aisdk.client.LLMClient#truncateIfNeeded}.
     * Inputs that exceed 95% of this limit throw
     * {@link com.syncvault.aisdk.exception.TokenLimitExceededException}; inputs between
     * 80–95% are silently truncated with an appended {@code "... [truncated]"} marker.
     * Defaults to {@code 100_000}.
     *
     * @param tokenLimit maximum tokens allowed per request
     */
    public LLMClientBuilder tokenLimit(int tokenLimit) {
        this.tokenLimit = tokenLimit;
        return this;
    }

    /**
     * When {@code true}, wraps the provider client with {@link ResilientLLMClient}
     * (retry + optional circuit breaker). When {@code false} (default), the raw
     * provider client is returned with no resilience layer — suitable for tests or
     * environments where failures should propagate immediately.
     *
     * @param enabled {@code true} to enable the resilience wrapper
     */
    public LLMClientBuilder resilient(boolean enabled) {
        this.resilient = enabled;
        return this;
    }

    /**
     * Constructs and returns the configured {@link LLMClient}.
     *
     * <p>Build order:
     * <ol>
     *   <li>Validates that {@code provider} and {@code apiKey} are set.</li>
     *   <li>Instantiates the concrete provider client.</li>
     *   <li>If {@code resilient} is {@code true}, wraps it with
     *       {@link ResilientLLMClient}.</li>
     * </ol>
     *
     * @return the fully configured client
     * @throws IllegalStateException if {@code provider} or {@code apiKey} is missing
     */
    public LLMClient build() {
        if (provider == null) {
            throw new IllegalStateException("provider() is required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("apiKey() is required");
        }

        TokenCounter tokenCounter = new TokenCounter();

        LLMClient client = switch (provider) {
            case OPENAI -> new OpenAIClient(apiKey, model, tokenLimit, tokenCounter);
            case CLAUDE -> new ClaudeClient(apiKey, model, tokenLimit, tokenCounter);
        };

        if (resilient) {
            return new ResilientLLMClient(client, maxRetries, circuitBreakerEnabled, tokenLimit);
        }
        return client;
    }

    public Provider getProvider()           { return provider; }
    public String getApiKey()               { return apiKey; }
    public String getModel()                { return model; }
    public int getMaxRetries()              { return maxRetries; }
    public boolean isCircuitBreakerEnabled(){ return circuitBreakerEnabled; }
    public int getTokenLimit()              { return tokenLimit; }
    public boolean isResilient()            { return resilient; }
}
