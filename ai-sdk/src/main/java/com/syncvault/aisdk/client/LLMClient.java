package com.syncvault.aisdk.client;

import com.syncvault.aisdk.exception.LLMProviderException;
import com.syncvault.aisdk.exception.TokenLimitExceededException;
import com.syncvault.aisdk.model.EmbeddingResponse;
import com.syncvault.aisdk.model.LLMRequest;
import com.syncvault.aisdk.model.LLMResponse;

/**
 * Provider-agnostic contract for all LLM operations used throughout SyncVault.
 *
 * <p>Callers depend only on this interface — never on {@code ClaudeClient} or
 * {@code OpenAIClient} directly. Switching providers requires only a single
 * {@link com.syncvault.aisdk.config.LLMClientBuilder#provider} change.
 *
 * <p>All network-bound methods ({@link #complete}, {@link #completeWithSystem},
 * {@link #embed}) may throw {@link LLMProviderException} on HTTP errors. When
 * wrapped by {@link com.syncvault.aisdk.resilience.ResilientLLMClient}, retryable
 * errors (429, 5xx) are automatically retried with exponential backoff before the
 * exception surfaces to the caller.
 */
public interface LLMClient {

    /**
     * Sends a fully specified {@link LLMRequest} to the provider and returns the
     * model's completion.
     *
     * @param request the request containing system prompt, user message, token budget,
     *                temperature, and optional stop sequences
     * @return the model's response including generated content and token usage
     * @throws LLMProviderException      on provider-side HTTP errors
     * @throws TokenLimitExceededException if the user message exceeds 95% of the
     *                                    configured token limit (see {@link #truncateIfNeeded})
     */
    LLMResponse complete(LLMRequest request);

    /**
     * Convenience overload for the common case of a single system + user message pair.
     * Delegates to {@link #complete} with a minimal {@link LLMRequest}.
     *
     * @param systemPrompt the system instruction that sets the model's behaviour
     * @param userMessage  the user turn content
     * @return the model's response
     * @throws LLMProviderException on provider-side HTTP errors
     */
    LLMResponse completeWithSystem(String systemPrompt, String userMessage);

    /**
     * Generates a dense vector embedding for the given text using the provider's
     * embedding model (e.g. {@code text-embedding-3-small} for OpenAI).
     *
     * <p>The returned vector has 1536 dimensions and is suitable for cosine-similarity
     * search via pgvector's {@code <=>} operator.
     *
     * @param text the text to embed; should be the document description or search query,
     *             never raw file bytes
     * @return an {@link EmbeddingResponse} containing the embedding vector and token usage
     * @throws LLMProviderException on provider-side HTTP errors
     */
    EmbeddingResponse embed(String text);

    /**
     * Counts the number of tokens in {@code text} using the provider's tokeniser
     * (cl100k_base BPE for OpenAI-compatible providers).
     *
     * <p>This is a local, CPU-only operation — no network call is made. Safe to call
     * from virtual threads and hot paths.
     *
     * @param text the text to count tokens for; {@code null} is treated as 0 tokens
     * @return the token count
     */
    int countTokens(String text);

    /**
     * Returns {@code true} if {@code text} fits within {@code maxTokens}.
     *
     * <p>Equivalent to {@code countTokens(text) <= maxTokens} but expressed as a
     * guard check. Local operation, no network call.
     *
     * @param text      the text to check
     * @param maxTokens the token budget ceiling
     * @return {@code true} if the text is within budget
     */
    boolean isWithinLimit(String text, int maxTokens);

    /**
     * Returns the provider identifier, e.g. {@code "openai"} or {@code "claude"}.
     * Used for logging and circuit-breaker naming.
     */
    String getProviderName();

    /**
     * Enforces a soft/hard token budget on {@code text} before it is sent to the model:
     * <ul>
     *   <li>{@code > 95%} of {@code maxTokens} — throws {@link TokenLimitExceededException}
     *       immediately; the caller must handle this before retrying.</li>
     *   <li>{@code > 80%} of {@code maxTokens} — truncates the text to 80% of the budget
     *       using binary search over character indices and appends {@code "... [truncated]"}
     *       so the model is aware content was cut.</li>
     *   <li>{@code ≤ 80%} — returns the original string unchanged.</li>
     * </ul>
     *
     * <p>The binary-search truncation calls {@link #countTokens} O(log n) times (n = string
     * length). Safe to call on virtual threads — no shared state or thread-locals are used.
     *
     * @param text      the text to check and possibly truncate
     * @param maxTokens the token budget for this call
     * @return the (possibly truncated) text, or the original if within budget
     * @throws TokenLimitExceededException if the text exceeds the hard 95% threshold
     */
    default String truncateIfNeeded(String text, int maxTokens) {
        if (text == null || text.isBlank()) return text;

        int tokens   = countTokens(text);
        int hard     = (int)(maxTokens * 0.95);
        int soft     = (int)(maxTokens * 0.80);

        if (tokens > hard) {
            throw new TokenLimitExceededException(tokens, maxTokens);
        }
        if (tokens > soft) {
            String suffix       = "... [truncated]";
            int    suffixTokens = countTokens(suffix);
            int    target       = soft - suffixTokens;

            int lo = 0, hi = text.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (countTokens(text.substring(0, mid)) <= target) lo = mid;
                else hi = mid - 1;
            }
            return text.substring(0, lo) + suffix;
        }
        return text;
    }
}
