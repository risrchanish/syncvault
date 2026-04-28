package com.syncvault.aisdk.model;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class LLMResponse {

    private final String content;

    private final int inputTokens;

    private final int outputTokens;

    private final int totalTokens;

    /** Which provider generated this response: "claude" or "openai". */
    private final String providerName;

    /** Exact model string used, e.g. "claude-sonnet-4-5" or "gpt-4o-mini". */
    private final String modelUsed;

    /** Why generation stopped: "stop", "length", or "error". */
    private final String finishReason;
}
