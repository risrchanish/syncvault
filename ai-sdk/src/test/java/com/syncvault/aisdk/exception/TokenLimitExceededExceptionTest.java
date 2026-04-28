package com.syncvault.aisdk.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenLimitExceededExceptionTest {

    @Test
    void constructor_storesTokenCountAndLimit() {
        TokenLimitExceededException ex = new TokenLimitExceededException(150_000, 100_000);

        assertThat(ex.getInputTokens()).isEqualTo(150_000);
        assertThat(ex.getLimit()).isEqualTo(100_000);
    }

    @Test
    void message_includesBothValues() {
        TokenLimitExceededException ex = new TokenLimitExceededException(95_001, 95_000);

        assertThat(ex.getMessage()).contains("95001").contains("95000");
    }

    @Test
    void isRuntimeException() {
        assertThat(new TokenLimitExceededException(1, 1))
                .isInstanceOf(RuntimeException.class);
    }
}
