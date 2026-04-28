package com.syncvault.aisdk.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.syncvault.aisdk.client.LLMClient;
import com.syncvault.aisdk.exception.LLMProviderException;
import com.syncvault.aisdk.model.EmbeddingResponse;
import com.syncvault.aisdk.model.LLMRequest;
import com.syncvault.aisdk.model.LLMResponse;
import com.syncvault.aisdk.token.TokenCounter;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

public class ClaudeClient implements LLMClient {

    static final String DEFAULT_BASE_URL      = "https://api.anthropic.com";
    static final String DEFAULT_MODEL         = "claude-sonnet-4-5";
    static final String PROVIDER_NAME         = "claude";
    static final String ANTHROPIC_VERSION     = "2023-06-01";

    private final WebClient webClient;
    private final String model;
    private final int tokenLimit;
    private final TokenCounter tokenCounter;

    /** Production constructor — always points to the real Anthropic endpoint. */
    public ClaudeClient(String apiKey, String model, int tokenLimit, TokenCounter tokenCounter) {
        this(apiKey, model, tokenLimit, tokenCounter, DEFAULT_BASE_URL);
    }

    /** Package-private — allows WireMock base URL injection in tests. */
    ClaudeClient(String apiKey, String model, int tokenLimit, TokenCounter tokenCounter, String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", ANTHROPIC_VERSION)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.model        = (model != null && !model.isBlank()) ? model : DEFAULT_MODEL;
        this.tokenLimit   = tokenLimit;
        this.tokenCounter = tokenCounter;
    }

    // -------------------------------------------------------------------------
    // LLMClient implementation
    // -------------------------------------------------------------------------

    @Override
    public LLMResponse complete(LLMRequest request) {
        // Claude system prompt is a top-level field, not a message with role "system"
        ClaudeRequest body = new ClaudeRequest(
                model,
                request.getMaxTokens(),
                request.getSystemPrompt(),           // null → omitted by @JsonInclude(NON_NULL)
                List.of(new ClaudeMessage("user", request.getUserMessage())),
                request.getTemperature(),
                request.getStopSequences()
        );

        ClaudeResponse response = webClient.post()
                .uri("/v1/messages")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        resp -> resp.bodyToMono(String.class)
                                .defaultIfEmpty("(no body)")
                                .map(err -> new LLMProviderException(
                                        "Claude error [" + resp.statusCode().value() + "]: " + err,
                                        resp.statusCode().value())))
                .bodyToMono(ClaudeResponse.class)
                .block();

        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            throw new LLMProviderException("Empty response from Anthropic Claude");
        }

        // Anthropic returns an array of typed content blocks; extract the first text block
        String text = response.getContent().stream()
                .filter(b -> "text".equals(b.getType()))
                .findFirst()
                .map(ClaudeContentBlock::getText)
                .orElse("");

        ClaudeUsage usage        = response.getUsage();
        int         inputTokens  = usage != null ? usage.getInputTokens()  : 0;
        int         outputTokens = usage != null ? usage.getOutputTokens() : 0;

        return LLMResponse.builder()
                .content(text)
                .inputTokens(inputTokens)
                .outputTokens(outputTokens)
                .totalTokens(inputTokens + outputTokens)
                .providerName(PROVIDER_NAME)
                .modelUsed(response.getModel() != null ? response.getModel() : model)
                .finishReason(response.getStopReason())
                .build();
    }

    @Override
    public LLMResponse completeWithSystem(String systemPrompt, String userMessage) {
        return complete(LLMRequest.builder()
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .build());
    }

    /**
     * Claude does not support embeddings.
     * Use {@link OpenAIClient} with {@code text-embedding-3-small} for vector generation.
     */
    @Override
    public EmbeddingResponse embed(String text) {
        throw new UnsupportedOperationException(
                "Claude does not support embeddings. Use OpenAIClient for embeddings.");
    }

    @Override
    public int countTokens(String text) {
        return tokenCounter.countTokens(text);
    }

    @Override
    public boolean isWithinLimit(String text, int maxTokens) {
        return tokenCounter.isWithinLimit(text, maxTokens);
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    // -------------------------------------------------------------------------
    // Internal request DTOs
    // -------------------------------------------------------------------------

    @Getter
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class ClaudeRequest {
        private final String              model;
        @JsonProperty("max_tokens")
        private final Integer             maxTokens;
        private final String              system;          // top-level; null → omitted
        private final List<ClaudeMessage> messages;
        private final Double              temperature;
        @JsonProperty("stop_sequences")
        private final List<String>        stopSequences;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    static class ClaudeMessage {
        private String role;
        private String content;
    }

    // -------------------------------------------------------------------------
    // Internal response DTOs
    // -------------------------------------------------------------------------

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ClaudeResponse {
        private List<ClaudeContentBlock> content;
        private String                   model;
        @JsonProperty("stop_reason")
        private String                   stopReason;
        private ClaudeUsage              usage;
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ClaudeContentBlock {
        private String type;
        private String text;
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ClaudeUsage {
        @JsonProperty("input_tokens")
        private int inputTokens;
        @JsonProperty("output_tokens")
        private int outputTokens;
    }
}
