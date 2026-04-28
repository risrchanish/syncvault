package com.syncvault.aisdk.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class LLMRequest {

    private final String systemPrompt;

    private final String userMessage;

    @Builder.Default
    private final Integer maxTokens = 1000;

    @Builder.Default
    private final Double temperature = 0.3;

    private final String model;

    private final List<String> stopSequences;
}
