package com.syncvault.aisdk.exception;

public class TokenLimitExceededException extends RuntimeException {

    private final int inputTokens;
    private final int limit;

    public TokenLimitExceededException(int inputTokens, int limit) {
        super(String.format("Input token count %d exceeds limit %d", inputTokens, limit));
        this.inputTokens = inputTokens;
        this.limit = limit;
    }

    public int getInputTokens() { return inputTokens; }
    public int getLimit()       { return limit; }
}
