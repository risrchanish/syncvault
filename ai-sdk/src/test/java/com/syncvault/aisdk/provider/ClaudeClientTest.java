package com.syncvault.aisdk.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.syncvault.aisdk.exception.LLMProviderException;
import com.syncvault.aisdk.model.LLMRequest;
import com.syncvault.aisdk.model.LLMResponse;
import com.syncvault.aisdk.token.TokenCounter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private ClaudeClient client;

    @BeforeEach
    void setUp() {
        client = new ClaudeClient("test-api-key", "claude-sonnet-4-5", 100_000, new TokenCounter(), wm.baseUrl());
    }

    // -------------------------------------------------------------------------
    // complete()
    // -------------------------------------------------------------------------

    @Test
    void complete_parsesContentBlockArrayAndUsage() {
        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "id": "msg_01",
                                    "type": "message",
                                    "role": "assistant",
                                    "content": [
                                        {"type": "text", "text": "Paris is the capital of France."}
                                    ],
                                    "model": "claude-sonnet-4-5",
                                    "stop_reason": "end_turn",
                                    "usage": {"input_tokens": 12, "output_tokens": 10}
                                }""")));

        LLMRequest  request  = LLMRequest.builder().userMessage("Capital of France?").build();
        LLMResponse response = client.complete(request);

        assertThat(response.getContent()).isEqualTo("Paris is the capital of France.");
        assertThat(response.getInputTokens()).isEqualTo(12);
        assertThat(response.getOutputTokens()).isEqualTo(10);
        assertThat(response.getTotalTokens()).isEqualTo(22);
        assertThat(response.getProviderName()).isEqualTo("claude");
        assertThat(response.getModelUsed()).isEqualTo("claude-sonnet-4-5");
        assertThat(response.getFinishReason()).isEqualTo("end_turn");
    }

    @Test
    void complete_withSystemPrompt_sendsSystemAsTopLevelField() {
        // Verify: system prompt is a top-level JSON field, NOT a message with role "system"
        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.system", equalTo("You are a concise assistant.")))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "content": [{"type": "text", "text": "Done."}],
                                    "model": "claude-sonnet-4-5",
                                    "stop_reason": "end_turn",
                                    "usage": {"input_tokens": 15, "output_tokens": 2}
                                }""")));

        LLMResponse response = client.complete(LLMRequest.builder()
                .systemPrompt("You are a concise assistant.")
                .userMessage("Say done.")
                .build());

        assertThat(response.getContent()).isEqualTo("Done.");
    }

    @Test
    void complete_withoutSystemPrompt_omitsSystemField() {
        // @JsonInclude(NON_NULL) must prevent "system": null appearing in the JSON
        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .withRequestBody(matchingJsonPath("$.system", absent()))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "content": [{"type": "text", "text": "Hi there."}],
                                    "model": "claude-sonnet-4-5",
                                    "stop_reason": "end_turn",
                                    "usage": {"input_tokens": 5, "output_tokens": 3}
                                }""")));

        LLMResponse response = client.complete(LLMRequest.builder().userMessage("Hi").build());
        assertThat(response.getContent()).isEqualTo("Hi there.");
    }

    @Test
    void complete_sendsRequiredAnthropicHeaders() {
        // Verify x-api-key and anthropic-version headers are included on every request
        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("test-api-key"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "content": [{"type": "text", "text": "OK"}],
                                    "model": "claude-sonnet-4-5",
                                    "stop_reason": "end_turn",
                                    "usage": {"input_tokens": 3, "output_tokens": 1}
                                }""")));

        LLMResponse response = client.complete(LLMRequest.builder().userMessage("ping").build());
        assertThat(response.getContent()).isEqualTo("OK");
    }

    @Test
    void complete_picksFirstTextContentBlock() {
        // Response has multiple content blocks; client picks the first text block
        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "content": [
                                        {"type": "thinking", "thinking": "Let me consider..."},
                                        {"type": "text", "text": "42 is the answer."}
                                    ],
                                    "model": "claude-sonnet-4-5",
                                    "stop_reason": "end_turn",
                                    "usage": {"input_tokens": 8, "output_tokens": 25}
                                }""")));

        LLMResponse response = client.complete(LLMRequest.builder().userMessage("Answer?").build());
        assertThat(response.getContent()).isEqualTo("42 is the answer.");
        assertThat(response.getTotalTokens()).isEqualTo(33);
    }

    @Test
    void complete_throwsLLMProviderException_on401() {
        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": {\"type\": \"authentication_error\", \"message\": \"Invalid API key\"}}")));

        assertThatThrownBy(() -> client.complete(LLMRequest.builder().userMessage("Hello").build()))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("Claude")
                .hasMessageContaining("401");
    }

    @Test
    void complete_throwsLLMProviderException_on429RateLimit() {
        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withBody("{\"error\": {\"type\": \"rate_limit_error\"}}")));

        assertThatThrownBy(() -> client.complete(LLMRequest.builder().userMessage("Hello").build()))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("429");
    }

    @Test
    void complete_throwsLLMProviderException_on500() {
        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withBody("{\"error\": \"Internal server error\"}")));

        assertThatThrownBy(() -> client.complete(LLMRequest.builder().userMessage("Hello").build()))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("500");
    }

    // -------------------------------------------------------------------------
    // completeWithSystem()
    // -------------------------------------------------------------------------

    @Test
    void completeWithSystem_delegatesToComplete() {
        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "content": [{"type": "text", "text": "Summarized."}],
                                    "model": "claude-sonnet-4-5",
                                    "stop_reason": "end_turn",
                                    "usage": {"input_tokens": 50, "output_tokens": 5}
                                }""")));

        LLMResponse response = client.completeWithSystem(
                "You are a document summarizer.", "Summarize: the quick brown fox...");
        assertThat(response.getContent()).isEqualTo("Summarized.");
    }

    // -------------------------------------------------------------------------
    // embed() — unsupported on Claude
    // -------------------------------------------------------------------------

    @Test
    void embed_throwsUnsupportedOperation() {
        assertThatThrownBy(() -> client.embed("some text"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("embeddings");
    }

    // -------------------------------------------------------------------------
    // Token counting and metadata
    // -------------------------------------------------------------------------

    @Test
    void getProviderName_returnsClaude() {
        assertThat(client.getProviderName()).isEqualTo("claude");
    }

    @Test
    void countTokens_returnsPositiveCountForNonEmptyText() {
        int count = client.countTokens("The quick brown fox jumps over the lazy dog");
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void isWithinLimit_returnsTrueWhenUnderLimit() {
        assertThat(client.isWithinLimit("hello", 100)).isTrue();
    }

    @Test
    void isWithinLimit_returnsFalseWhenOverLimit() {
        assertThat(client.isWithinLimit("word ".repeat(1000), 10)).isFalse();
    }
}
