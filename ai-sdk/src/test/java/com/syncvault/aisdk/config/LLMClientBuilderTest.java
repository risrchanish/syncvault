package com.syncvault.aisdk.config;

import com.syncvault.aisdk.client.LLMClient;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LLMClientBuilderTest {

    @Test
    void build_withOpenAI_createsClientWithCorrectProvider() {
        LLMClient client = LLMClientBuilder.create()
                .provider(Provider.OPENAI)
                .apiKey("test-key")
                .model("gpt-4o-mini")
                .maxRetries(3)
                .circuitBreaker(true)
                .tokenLimit(50_000)
                .build();

        assertThat(client).isNotNull();
        assertThat(client.getProviderName()).isEqualTo("openai");
    }

    @Test
    void build_throwsIllegalState_whenProviderNull() {
        assertThatThrownBy(() -> LLMClientBuilder.create()
                .apiKey("test-key")
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("provider");
    }

    @Test
    void build_throwsIllegalState_whenApiKeyBlank() {
        assertThatThrownBy(() -> LLMClientBuilder.create()
                .provider(Provider.OPENAI)
                .apiKey("  ")
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void build_throwsIllegalState_whenApiKeyNull() {
        assertThatThrownBy(() -> LLMClientBuilder.create()
                .provider(Provider.OPENAI)
                .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void build_withClaude_createsClientWithCorrectProvider() {
        LLMClient client = LLMClientBuilder.create()
                .provider(Provider.CLAUDE)
                .apiKey("test-key")
                .model("claude-sonnet-4-5")
                .build();

        assertThat(client).isNotNull();
        assertThat(client.getProviderName()).isEqualTo("claude");
    }

    @Test
    void getters_returnAllConfiguredValues() {
        LLMClientBuilder builder = LLMClientBuilder.create()
                .provider(Provider.OPENAI)
                .apiKey("my-key")
                .model("gpt-4o")
                .maxRetries(5)
                .circuitBreaker(true)
                .tokenLimit(200_000)
                .resilient(true);

        assertThat(builder.getProvider()).isEqualTo(Provider.OPENAI);
        assertThat(builder.getApiKey()).isEqualTo("my-key");
        assertThat(builder.getModel()).isEqualTo("gpt-4o");
        assertThat(builder.getMaxRetries()).isEqualTo(5);
        assertThat(builder.isCircuitBreakerEnabled()).isTrue();
        assertThat(builder.getTokenLimit()).isEqualTo(200_000);
        assertThat(builder.isResilient()).isTrue();
    }

    @Test
    void defaults_areApplied_whenNotSet() {
        LLMClientBuilder builder = LLMClientBuilder.create();

        assertThat(builder.getMaxRetries()).isEqualTo(3);
        assertThat(builder.isCircuitBreakerEnabled()).isFalse();
        assertThat(builder.getTokenLimit()).isEqualTo(100_000);
        assertThat(builder.isResilient()).isFalse();
    }

    @Test
    void build_withResilient_wrapsClientWithResilientDecorator() {
        LLMClient client = LLMClientBuilder.create()
                .provider(Provider.OPENAI)
                .apiKey("test-key")
                .resilient(true)
                .build();

        assertThat(client).isNotNull();
        assertThat(client.getProviderName()).isEqualTo("openai-resilient");
    }
}
