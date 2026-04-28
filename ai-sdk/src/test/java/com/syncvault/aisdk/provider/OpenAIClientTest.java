package com.syncvault.aisdk.provider;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.syncvault.aisdk.exception.LLMProviderException;
import com.syncvault.aisdk.model.EmbeddingResponse;
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

class OpenAIClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private OpenAIClient client;

    @BeforeEach
    void setUp() {
        client = new OpenAIClient("test-api-key", "gpt-4o-mini", 100_000, new TokenCounter(), wm.baseUrl());
    }

    // -------------------------------------------------------------------------
    // complete()
    // -------------------------------------------------------------------------

    @Test
    void complete_returnsLLMResponse_onSuccess() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "choices": [
                                        {
                                            "message": {"role": "assistant", "content": "Paris"},
                                            "finish_reason": "stop"
                                        }
                                    ],
                                    "usage": {
                                        "prompt_tokens": 12,
                                        "completion_tokens": 3,
                                        "total_tokens": 15
                                    },
                                    "model": "gpt-4o-mini"
                                }""")));

        LLMRequest  request  = LLMRequest.builder().userMessage("What is the capital of France?").build();
        LLMResponse response = client.complete(request);

        assertThat(response.getContent()).isEqualTo("Paris");
        assertThat(response.getInputTokens()).isEqualTo(12);
        assertThat(response.getOutputTokens()).isEqualTo(3);
        assertThat(response.getTotalTokens()).isEqualTo(15);
        assertThat(response.getProviderName()).isEqualTo("openai");
        assertThat(response.getModelUsed()).isEqualTo("gpt-4o-mini");
        assertThat(response.getFinishReason()).isEqualTo("stop");
    }

    @Test
    void complete_withSystemPrompt_sendsSystemMessageFirst() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("system")))
                .withRequestBody(matchingJsonPath("$.messages[1].role", equalTo("user")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "choices": [{"message": {"role": "assistant", "content": "OK"}, "finish_reason": "stop"}],
                                    "usage": {"prompt_tokens": 20, "completion_tokens": 1, "total_tokens": 21},
                                    "model": "gpt-4o-mini"
                                }""")));

        LLMRequest request = LLMRequest.builder()
                .systemPrompt("You are a helpful assistant.")
                .userMessage("Say OK")
                .build();

        LLMResponse response = client.complete(request);
        assertThat(response.getContent()).isEqualTo("OK");
    }

    @Test
    void complete_withoutSystemPrompt_sendsOnlyUserMessage() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.messages[0].role", equalTo("user")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "choices": [{"message": {"role": "assistant", "content": "Hi"}, "finish_reason": "stop"}],
                                    "usage": {"prompt_tokens": 5, "completion_tokens": 1, "total_tokens": 6},
                                    "model": "gpt-4o-mini"
                                }""")));

        LLMResponse response = client.complete(LLMRequest.builder().userMessage("Hi").build());
        assertThat(response.getContent()).isEqualTo("Hi");

        // Verify only one message was sent (no system message)
        wm.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.messages[?(@.role == 'system')]", absent())));
    }

    @Test
    void complete_throwsLLMProviderException_on401() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": {\"message\": \"Invalid API key\"}}")));

        assertThatThrownBy(() -> client.complete(LLMRequest.builder().userMessage("Hello").build()))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("OpenAI")
                .hasMessageContaining("401");
    }

    @Test
    void complete_throwsLLMProviderException_on500() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"Internal server error\"}")));

        assertThatThrownBy(() -> client.complete(LLMRequest.builder().userMessage("Hello").build()))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("OpenAI")
                .hasMessageContaining("500");
    }

    @Test
    void complete_throwsLLMProviderException_on429RateLimit() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withBody("{\"error\": {\"message\": \"Rate limit exceeded\"}}")));

        assertThatThrownBy(() -> client.complete(LLMRequest.builder().userMessage("Hello").build()))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("429");
    }

    // -------------------------------------------------------------------------
    // completeWithSystem()
    // -------------------------------------------------------------------------

    @Test
    void completeWithSystem_delegatesToComplete() {
        wm.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "choices": [{"message": {"role": "assistant", "content": "Done"}, "finish_reason": "stop"}],
                                    "usage": {"prompt_tokens": 5, "completion_tokens": 1, "total_tokens": 6},
                                    "model": "gpt-4o-mini"
                                }""")));

        LLMResponse response = client.completeWithSystem("Be concise.", "Summarize: the quick brown fox");
        assertThat(response.getContent()).isEqualTo("Done");
    }

    // -------------------------------------------------------------------------
    // embed()
    // -------------------------------------------------------------------------

    @Test
    void embed_returnsEmbeddingResponse_onSuccess() {
        wm.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "data": [{"embedding": [0.1, 0.2, 0.3], "index": 0}],
                                    "usage": {"prompt_tokens": 4, "total_tokens": 4},
                                    "model": "text-embedding-3-small"
                                }""")));

        EmbeddingResponse response = client.embed("hello world");

        assertThat(response.getEmbedding()).containsExactly(0.1, 0.2, 0.3);
        assertThat(response.getInputTokens()).isEqualTo(4);
        assertThat(response.getModelUsed()).isEqualTo("text-embedding-3-small");
    }

    @Test
    void embed_usesEmbeddingModel_inRequestBody() {
        wm.stubFor(post(urlEqualTo("/v1/embeddings"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("text-embedding-3-small")))
                .withRequestBody(matchingJsonPath("$.input", equalTo("semantic search")))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                    "data": [{"embedding": [0.5], "index": 0}],
                                    "usage": {"prompt_tokens": 2, "total_tokens": 2},
                                    "model": "text-embedding-3-small"
                                }""")));

        EmbeddingResponse response = client.embed("semantic search");
        assertThat(response.getEmbedding()).containsExactly(0.5);
    }

    @Test
    void embed_throwsLLMProviderException_on4xxError() {
        wm.stubFor(post(urlEqualTo("/v1/embeddings"))
                .willReturn(aResponse()
                        .withStatus(429)
                        .withBody("{\"error\": {\"message\": \"Rate limit exceeded\"}}")));

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("429");
    }

    // -------------------------------------------------------------------------
    // Token counting (delegates to TokenCounter — no HTTP call needed)
    // -------------------------------------------------------------------------

    @Test
    void getProviderName_returnsOpenai() {
        assertThat(client.getProviderName()).isEqualTo("openai");
    }

    @Test
    void countTokens_returnsPositiveCountForNonEmptyText() {
        int count = client.countTokens("The quick brown fox jumps over the lazy dog");
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void countTokens_returnsZeroForBlankText() {
        assertThat(client.countTokens("")).isZero();
        assertThat(client.countTokens("   ")).isZero();
        assertThat(client.countTokens(null)).isZero();
    }

    @Test
    void isWithinLimit_returnsTrueWhenUnderLimit() {
        assertThat(client.isWithinLimit("hello", 100)).isTrue();
    }

    @Test
    void isWithinLimit_returnsFalseWhenOverLimit() {
        String longText = "word ".repeat(1000);
        assertThat(client.isWithinLimit(longText, 10)).isFalse();
    }
}
