package com.syncvault.aisdk.token;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCounterTest {

    private TokenCounter tokenCounter;

    // A sentence that tokenises to a predictable, non-trivial number of tokens
    private static final String SENTENCE = "The quick brown fox jumps over the lazy dog.";

    @BeforeEach
    void setUp() {
        tokenCounter = new TokenCounter();
    }

    // -------------------------------------------------------------------------
    // countTokens
    // -------------------------------------------------------------------------

    @Test
    void countTokens_returnsZero_forNullText() {
        assertThat(tokenCounter.countTokens(null)).isEqualTo(0);
    }

    @Test
    void countTokens_returnsZero_forBlankText() {
        assertThat(tokenCounter.countTokens("   ")).isEqualTo(0);
    }

    @Test
    void countTokens_returnsPositive_forNonEmptyText() {
        assertThat(tokenCounter.countTokens(SENTENCE)).isGreaterThan(0);
    }

    @Test
    void countTokens_longerText_hasMoreTokens() {
        int single = tokenCounter.countTokens(SENTENCE);
        int doubled = tokenCounter.countTokens(SENTENCE + " " + SENTENCE);
        assertThat(doubled).isGreaterThan(single);
    }

    // -------------------------------------------------------------------------
    // isWithinLimit
    // -------------------------------------------------------------------------

    @Test
    void isWithinLimit_returnsTrue_whenTokenCountBelowLimit() {
        int actual = tokenCounter.countTokens(SENTENCE);
        assertThat(tokenCounter.isWithinLimit(SENTENCE, actual + 10)).isTrue();
    }

    @Test
    void isWithinLimit_returnsTrue_whenExactlyAtLimit() {
        int actual = tokenCounter.countTokens(SENTENCE);
        assertThat(tokenCounter.isWithinLimit(SENTENCE, actual)).isTrue();
    }

    @Test
    void isWithinLimit_returnsFalse_whenOverLimit() {
        int actual = tokenCounter.countTokens(SENTENCE);
        assertThat(tokenCounter.isWithinLimit(SENTENCE, actual - 1)).isFalse();
    }

    // -------------------------------------------------------------------------
    // truncateToLimit
    // -------------------------------------------------------------------------

    @Test
    void truncateToLimit_returnsOriginal_whenAlreadyWithinLimit() {
        int limit = tokenCounter.countTokens(SENTENCE) + 50;
        assertThat(tokenCounter.truncateToLimit(SENTENCE, limit)).isEqualTo(SENTENCE);
    }

    @Test
    void truncateToLimit_producesTruncatedSuffix_whenOverLimit() {
        // Long text guaranteed to exceed 20 tokens
        String longText = (SENTENCE + " ").repeat(5);
        String result = tokenCounter.truncateToLimit(longText, 20);

        assertThat(result).endsWith("... [truncated]");
        assertThat(tokenCounter.countTokens(result)).isLessThanOrEqualTo(20);
    }

    @Test
    void truncateToLimit_resultFitsWithinLimit() {
        String longText = (SENTENCE + " ").repeat(10);
        int limit = 30;
        String result = tokenCounter.truncateToLimit(longText, limit);

        assertThat(tokenCounter.countTokens(result)).isLessThanOrEqualTo(limit);
    }

    @Test
    void truncateToLimit_preservesMaximumContent_withinLimit() {
        String longText = (SENTENCE + " ").repeat(10);
        int limit = 40;
        String result = tokenCounter.truncateToLimit(longText, limit);

        // The result should be near the limit — at least half of it should be used
        assertThat(tokenCounter.countTokens(result)).isGreaterThan(limit / 2);
    }

    @Test
    void truncateToLimit_handlesNullText() {
        assertThat(tokenCounter.truncateToLimit(null, 100)).isEqualTo("");
    }

    @Test
    void truncateToLimit_handlesBlankText() {
        assertThat(tokenCounter.truncateToLimit("   ", 100)).isEqualTo("   ");
    }

    // -------------------------------------------------------------------------
    // truncateToPercent
    // -------------------------------------------------------------------------

    @Test
    void truncateToPercent_targetIs80Percent_ofLimit() {
        String longText = (SENTENCE + " ").repeat(10);
        int maxTokens = 100;
        double percent = 0.80;
        int target = (int)(maxTokens * percent); // 80

        String result = tokenCounter.truncateToPercent(longText, percent, maxTokens);

        assertThat(tokenCounter.countTokens(result)).isLessThanOrEqualTo(target);
        assertThat(result).endsWith("... [truncated]");
    }

    @Test
    void truncateToPercent_returnsOriginal_whenTextFitsInPercent() {
        // SENTENCE is ~10 tokens; 80% of 1000 = 800 → no truncation needed
        String result = tokenCounter.truncateToPercent(SENTENCE, 0.80, 1000);
        assertThat(result).isEqualTo(SENTENCE);
    }

    @Test
    void truncateToPercent_differentPercents_produceDifferentResults() {
        String longText = (SENTENCE + " ").repeat(10);
        String at60 = tokenCounter.truncateToPercent(longText, 0.60, 100);
        String at90 = tokenCounter.truncateToPercent(longText, 0.90, 100);

        // 90% target allows more tokens than 60%
        assertThat(tokenCounter.countTokens(at90))
                .isGreaterThanOrEqualTo(tokenCounter.countTokens(at60));
    }
}
