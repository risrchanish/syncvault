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

import java.util.ArrayList;
import java.util.List;

public class OpenAIClient implements LLMClient {

    static final String DEFAULT_BASE_URL = "https://api.openai.com";
    static final String DEFAULT_MODEL    = "gpt-4o-mini";
    static final String EMBEDDING_MODEL  = "text-embedding-3-small";
    static final String PROVIDER_NAME    = "openai";

    private final WebClient webClient;
    private final String model;
    private final int tokenLimit;
    private final TokenCounter tokenCounter;

    /** Production constructor — always points to the real OpenAI endpoint. */
    public OpenAIClient(String apiKey, String model, int tokenLimit, TokenCounter tokenCounter) {
        this(apiKey, model, tokenLimit, tokenCounter, DEFAULT_BASE_URL);
    }

    /** Package-private — allows WireMock base URL injection in tests. */
    OpenAIClient(String apiKey, String model, int tokenLimit, TokenCounter tokenCounter, String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
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
        List<ChatMessage> messages = new ArrayList<>();
        if (request.getSystemPrompt() != null) {
            messages.add(new ChatMessage("system", request.getSystemPrompt()));
        }
        messages.add(new ChatMessage("user", request.getUserMessage()));

        ChatCompletionRequest body = new ChatCompletionRequest(
                model,
                messages,
                request.getMaxTokens(),
                request.getTemperature(),
                request.getStopSequences()
        );

        ChatCompletionResponse response = webClient.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        resp -> resp.bodyToMono(String.class)
                                .defaultIfEmpty("(no body)")
                                .map(err -> new LLMProviderException(
                                        "OpenAI chat error [" + resp.statusCode().value() + "]: " + err,
                                        resp.statusCode().value())))
                .bodyToMono(ChatCompletionResponse.class)
                .block();

        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new LLMProviderException("Empty response from OpenAI chat completions");
        }

        ChatChoice choice = response.getChoices().get(0);
        ChatUsage  usage  = response.getUsage();

        return LLMResponse.builder()
                .content(choice.getMessage() != null ? choice.getMessage().getContent() : "")
                .inputTokens(usage != null ? usage.getPromptTokens() : 0)
                .outputTokens(usage != null ? usage.getCompletionTokens() : 0)
                .totalTokens(usage != null ? usage.getTotalTokens() : 0)
                .providerName(PROVIDER_NAME)
                .modelUsed(response.getModel() != null ? response.getModel() : model)
                .finishReason(choice.getFinishReason())
                .build();
    }

    @Override
    public LLMResponse completeWithSystem(String systemPrompt, String userMessage) {
        return complete(LLMRequest.builder()
                .systemPrompt(systemPrompt)
                .userMessage(userMessage)
                .build());
    }

    @Override
    public EmbeddingResponse embed(String text) {
        EmbeddingRequest body = new EmbeddingRequest(EMBEDDING_MODEL, text);

        EmbeddingApiResponse response = webClient.post()
                .uri("/v1/embeddings")
                .bodyValue(body)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        resp -> resp.bodyToMono(String.class)
                                .defaultIfEmpty("(no body)")
                                .map(err -> new LLMProviderException(
                                        "OpenAI embeddings error [" + resp.statusCode().value() + "]: " + err,
                                        resp.statusCode().value())))
                .bodyToMono(EmbeddingApiResponse.class)
                .block();

        if (response == null || response.getData() == null || response.getData().isEmpty()) {
            throw new LLMProviderException("Empty response from OpenAI embeddings");
        }

        EmbeddingData  data  = response.getData().get(0);
        EmbeddingUsage usage = response.getUsage();

        return EmbeddingResponse.builder()
                .embedding(data.getEmbedding())
                .inputTokens(usage != null ? usage.getPromptTokens() : 0)
                .modelUsed(response.getModel() != null ? response.getModel() : EMBEDDING_MODEL)
                .build();
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
    static class ChatCompletionRequest {
        private final String          model;
        private final List<ChatMessage> messages;
        @JsonProperty("max_tokens")
        private final Integer         maxTokens;
        private final Double          temperature;
        @JsonProperty("stop")
        private final List<String>    stopSequences;
    }

    @Getter @Setter
    @NoArgsConstructor @AllArgsConstructor
    static class ChatMessage {
        private String role;
        private String content;
    }

    @Getter
    @AllArgsConstructor
    static class EmbeddingRequest {
        private final String model;
        private final String input;
    }

    // -------------------------------------------------------------------------
    // Internal response DTOs
    // -------------------------------------------------------------------------

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatCompletionResponse {
        private List<ChatChoice> choices;
        private ChatUsage        usage;
        private String           model;
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatChoice {
        private ChatMessage message;
        @JsonProperty("finish_reason")
        private String      finishReason;
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ChatUsage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;
        @JsonProperty("completion_tokens")
        private int completionTokens;
        @JsonProperty("total_tokens")
        private int totalTokens;
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbeddingApiResponse {
        private List<EmbeddingData> data;
        private EmbeddingUsage      usage;
        private String              model;
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbeddingData {
        private List<Double> embedding;
        private int          index;
    }

    @Getter @Setter @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class EmbeddingUsage {
        @JsonProperty("prompt_tokens")
        private int promptTokens;
        @JsonProperty("total_tokens")
        private int totalTokens;
    }
}
