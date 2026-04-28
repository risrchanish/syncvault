package com.syncvault.aisdk.exception;

public class LLMProviderException extends RuntimeException {

    private final int statusCode;

    public LLMProviderException(String message) {
        super(message);
        this.statusCode = 0;
    }

    /** Used by providers to attach the HTTP status so retry predicates can inspect it. */
    public LLMProviderException(String message, int statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public LLMProviderException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /** Returns true for transient server-side errors that are worth retrying. */
    public boolean isRetryable() {
        return statusCode == 429
                || statusCode == 500
                || statusCode == 502
                || statusCode == 503
                || statusCode == 504;
    }
}
