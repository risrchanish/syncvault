package com.syncvault.aisdk.client;

import com.syncvault.aisdk.exception.TokenLimitExceededException;
import com.syncvault.aisdk.provider.OpenAIClient;
import com.syncvault.aisdk.token.TokenCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests {@link LLMClient#truncateIfNeeded} default method through a real client
 * (OpenAIClient) so that {@code countTokens()} uses actual JTokkit BPE encoding.
 *
 * Limits are derived from measured token counts so no assumptions/skips occur.
 */
class LLMClientTruncationTest {

    private LLMClient client;
    private TokenCounter tokenCounter;

    // ~10 tokens; 5 repetitions → ~50 tokens (reliable baseline)
    private static final String BASE_SENTENCE = "The quick brown fox jumps over the lazy dog. ";

    @BeforeEach
    void setUp() {
        tokenCounter = new TokenCounter();
        client = new OpenAIClient("dummy-key", null, 100_000, tokenCounter);
    }

    // -------------------------------------------------------------------------
    // Within safe zone (≤ 80%) — return same object unchanged
    // -------------------------------------------------------------------------

    @Test
    void truncateIfNeeded_returnsUnchanged_whenBelowSoftThreshold() {
        String text  = BASE_SENTENCE; // ~10 tokens
        int    limit = 10_000;        // 80% = 8000 → far above 10 tokens
        assertThat(client.truncateIfNeeded(text, limit)).isSameAs(text);
    }

    @Test
    void truncateIfNeeded_handlesNullText() {
        assertThat(client.truncateIfNeeded(null, 100)).isNull();
    }

    @Test
    void truncateIfNeeded_handlesBlankText() {
        assertThat(client.truncateIfNeeded("  ", 100)).isEqualTo("  ");
    }

    // -------------------------------------------------------------------------
    // Between 80% and 95% — truncate to 80%
    // Limit is derived from actual token count so the text sits at ~87.5% of limit.
    // -------------------------------------------------------------------------

    @Test
    void truncateIfNeeded_truncatesTo80Percent_whenBetweenSoftAndHardThreshold() {
        String text   = BASE_SENTENCE.repeat(5);
        int    tokens = tokenCounter.countTokens(text);

        // Setting limit = tokens / 0.875 places text at 87.5% of limit (in the 80-95 band)
        int limit = (int)(tokens / 0.875);
        int soft  = (int)(limit * 0.80);
        int hard  = (int)(limit * 0.95);

        // Confirm precondition holds with real token count
        assertThat(tokens).isGreaterThan(soft);
        assertThat(tokens).isLessThanOrEqualTo(hard);

        String result = client.truncateIfNeeded(text, limit);

        assertThat(result).endsWith("... [truncated]");
        assertThat(tokenCounter.countTokens(result)).isLessThanOrEqualTo(soft);
    }

    @Test
    void truncateIfNeeded_truncatedResult_preservesMaximumContentWithin80Percent() {
        String text   = BASE_SENTENCE.repeat(5);
        int    tokens = tokenCounter.countTokens(text);
        int    limit  = (int)(tokens / 0.875);
        int    soft   = (int)(limit * 0.80);

        String result = client.truncateIfNeeded(text, limit);

        // Result must fit within soft budget
        assertThat(tokenCounter.countTokens(result)).isLessThanOrEqualTo(soft);
        // Result should use at least half the soft budget (not aggressively over-trimmed)
        assertThat(tokenCounter.countTokens(result)).isGreaterThan(soft / 2);
    }

    // -------------------------------------------------------------------------
    // Over 95% — throw TokenLimitExceededException
    // Limit is derived so the text sits at ~96% of limit (above 95% hard boundary).
    // -------------------------------------------------------------------------

    @Test
    void truncateIfNeeded_throwsTokenLimitExceeded_whenOverHardThreshold() {
        String text   = BASE_SENTENCE.repeat(5);
        int    tokens = tokenCounter.countTokens(text);

        // Setting limit = tokens / 0.96 places text at 96% of limit (above the 95% hard ceiling)
        int limit = (int)(tokens / 0.96);
        int hard  = (int)(limit * 0.95);

        // Confirm precondition holds
        assertThat(tokens).isGreaterThan(hard);

        assertThatThrownBy(() -> client.truncateIfNeeded(text, limit))
                .isInstanceOf(TokenLimitExceededException.class)
                .satisfies(e -> {
                    TokenLimitExceededException ex = (TokenLimitExceededException) e;
                    assertThat(ex.getInputTokens()).isEqualTo(tokens);
                    assertThat(ex.getLimit()).isEqualTo(limit);
                });
    }

    @Test
    void truncateIfNeeded_exceptionMessage_containsInputTokensAndLimit() {
        String text   = BASE_SENTENCE.repeat(5);
        int    tokens = tokenCounter.countTokens(text);
        int    limit  = (int)(tokens / 0.96);

        assertThatThrownBy(() -> client.truncateIfNeeded(text, limit))
                .isInstanceOf(TokenLimitExceededException.class)
                .hasMessageContaining(String.valueOf(tokens))
                .hasMessageContaining(String.valueOf(limit));
    }

    // -------------------------------------------------------------------------
    // Virtual thread compatibility
    // No synchronized blocks or thread-locals pin a virtual thread during token ops.
    // -------------------------------------------------------------------------

    @Test
    void truncateIfNeeded_runsOnVirtualThread_withoutBlocking() throws InterruptedException {
        String   text   = BASE_SENTENCE;
        int      limit  = 10_000;
        String[] result = {null};

        Thread vt = Thread.ofVirtual().start(() -> result[0] = client.truncateIfNeeded(text, limit));
        vt.join();

        assertThat(result[0]).isSameAs(text);
    }

    @Test
    void truncateToLimit_onVirtualThread_producesCorrectResult() throws InterruptedException {
        String   longText = BASE_SENTENCE.repeat(5);
        int      tokens   = tokenCounter.countTokens(longText);
        int      limit    = (int)(tokens / 0.875);
        String[] result   = {null};

        Thread vt = Thread.ofVirtual().start(
                () -> result[0] = client.truncateIfNeeded(longText, limit));
        vt.join();

        assertThat(result[0]).endsWith("... [truncated]");
        assertThat(tokenCounter.countTokens(result[0])).isLessThanOrEqualTo((int)(limit * 0.80));
    }
}
