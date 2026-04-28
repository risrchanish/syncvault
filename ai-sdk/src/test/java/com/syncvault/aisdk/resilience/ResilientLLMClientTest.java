package com.syncvault.aisdk.resilience;

import com.syncvault.aisdk.client.LLMClient;
import com.syncvault.aisdk.exception.LLMProviderException;
import com.syncvault.aisdk.model.EmbeddingResponse;
import com.syncvault.aisdk.model.LLMRequest;
import com.syncvault.aisdk.model.LLMResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.RetryConfig;
import com.syncvault.aisdk.exception.TokenLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResilientLLMClientTest {

    @Mock
    private LLMClient delegate;

    private static final LLMRequest REQUEST = LLMRequest.builder().userMessage("hello").build();

    private static final LLMResponse SUCCESS_RESPONSE = LLMResponse.builder()
            .content("ok")
            .inputTokens(5)
            .outputTokens(3)
            .totalTokens(8)
            .providerName("openai")
            .modelUsed("gpt-4o-mini")
            .finishReason("stop")
            .build();

    /** 3 attempts, 10ms wait — fast for tests. */
    private RetryConfig fastRetry() {
        return RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(10))
                .retryOnException(e -> e instanceof LLMProviderException && ((LLMProviderException) e).isRetryable())
                .build();
    }

    /** CB opens after 2 calls at ≥50% failure; HALF_OPEN after 100 ms. */
    private CircuitBreakerConfig fastCb() {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofMillis(100))
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(e -> e instanceof LLMProviderException && ((LLMProviderException) e).isRetryable())
                .build();
    }

    @BeforeEach
    void setUp() {
        when(delegate.getProviderName()).thenReturn("openai");
        // truncateIfNeeded is a default method; call real impl — with countTokens() returning 0
        // by default the real method returns text unchanged (0 tokens is always within any limit)
        when(delegate.truncateIfNeeded(any(), anyInt())).thenCallRealMethod();
    }

    @Test
    void complete_returnsSuccess_onFirstAttempt() {
        when(delegate.complete(REQUEST)).thenReturn(SUCCESS_RESPONSE);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null);

        LLMResponse result = client.complete(REQUEST);

        assertThat(result.getContent()).isEqualTo("ok");
        verify(delegate, times(1)).complete(REQUEST);
    }

    @Test
    void complete_retries_on429_thenSucceeds() {
        LLMProviderException rateLimited = new LLMProviderException("rate limited", 429);
        when(delegate.complete(REQUEST))
                .thenThrow(rateLimited)
                .thenReturn(SUCCESS_RESPONSE);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null);

        LLMResponse result = client.complete(REQUEST);

        assertThat(result.getContent()).isEqualTo("ok");
        verify(delegate, times(2)).complete(REQUEST);
    }

    @Test
    void complete_retries_on500_thenSucceeds() {
        LLMProviderException serverError = new LLMProviderException("server error", 500);
        when(delegate.complete(REQUEST))
                .thenThrow(serverError)
                .thenThrow(serverError)
                .thenReturn(SUCCESS_RESPONSE);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null);

        LLMResponse result = client.complete(REQUEST);

        assertThat(result.getContent()).isEqualTo("ok");
        verify(delegate, times(3)).complete(REQUEST);
    }

    @Test
    void complete_exhaustsRetries_throwsLLMProviderException() {
        LLMProviderException serverError = new LLMProviderException("server error", 500);
        when(delegate.complete(REQUEST)).thenThrow(serverError);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null);

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("server error");

        verify(delegate, times(3)).complete(REQUEST);
    }

    @Test
    void complete_doesNotRetry_on401() {
        LLMProviderException unauthorized = new LLMProviderException("unauthorized", 401);
        when(delegate.complete(REQUEST)).thenThrow(unauthorized);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null);

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("unauthorized");

        verify(delegate, times(1)).complete(REQUEST);
    }

    @Test
    void complete_doesNotRetry_on400() {
        LLMProviderException badRequest = new LLMProviderException("bad request", 400);
        when(delegate.complete(REQUEST)).thenThrow(badRequest);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null);

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("bad request");

        verify(delegate, times(1)).complete(REQUEST);
    }

    /**
     * CB requires minimumNumberOfCalls(2) fully-failed calls.
     * Each client call retries 3 times, so we need 6 delegate failures to exhaust 2 client calls.
     */
    @Test
    void circuitBreaker_opensAfterThreshold_throwsCircuitOpenException() {
        LLMProviderException serverError = new LLMProviderException("server error", 500);
        // 6 failures: 3 per client call (maxAttempts=3) × 2 client calls to trip the CB
        when(delegate.complete(REQUEST)).thenThrow(
                serverError, serverError, serverError,
                serverError, serverError, serverError);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), fastCb());

        assertThatThrownBy(() -> client.complete(REQUEST)).isInstanceOf(LLMProviderException.class);
        assertThatThrownBy(() -> client.complete(REQUEST)).isInstanceOf(LLMProviderException.class);

        // CB is now OPEN — next call fails fast without reaching delegate
        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(LLMProviderException.class)
                .hasMessageContaining("Circuit breaker is OPEN");
    }

    /**
     * After the OPEN wait expires, CB transitions to HALF_OPEN.
     * A successful probe call closes the CB and subsequent calls succeed.
     */
    @Test
    void circuitBreaker_recovers_afterOpenWait() throws InterruptedException {
        LLMProviderException serverError = new LLMProviderException("server error", 500);
        // 6 failures for 2 client calls, then success for the HALF_OPEN probe
        when(delegate.complete(REQUEST))
                .thenThrow(serverError, serverError, serverError,
                           serverError, serverError, serverError)
                .thenReturn(SUCCESS_RESPONSE);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), fastCb());

        assertThatThrownBy(() -> client.complete(REQUEST)).isInstanceOf(LLMProviderException.class);
        assertThatThrownBy(() -> client.complete(REQUEST)).isInstanceOf(LLMProviderException.class);

        Thread.sleep(150); // wait past the 100ms OPEN window

        LLMResponse result = client.complete(REQUEST);
        assertThat(result.getContent()).isEqualTo("ok");
    }

    @Test
    void embed_retriesOnError() {
        EmbeddingResponse embeddingResponse = EmbeddingResponse.builder()
                .embedding(List.of(0.1, 0.2))
                .inputTokens(3)
                .modelUsed("text-embedding-3-small")
                .build();

        LLMProviderException serverError = new LLMProviderException("server error", 503);
        when(delegate.embed("text"))
                .thenThrow(serverError)
                .thenReturn(embeddingResponse);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null);

        EmbeddingResponse result = client.embed("text");

        assertThat(result.getEmbedding()).containsExactly(0.1, 0.2);
        verify(delegate, times(2)).embed("text");
    }

    @Test
    void countTokens_bypassesResilience_callsDelegateDirectly() {
        when(delegate.countTokens("hello world")).thenReturn(2);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null);

        assertThat(client.countTokens("hello world")).isEqualTo(2);
        verify(delegate, times(1)).countTokens("hello world");
    }

    @Test
    void isWithinLimit_bypassesResilience_callsDelegateDirectly() {
        when(delegate.isWithinLimit("hello", 100)).thenReturn(true);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null);

        assertThat(client.isWithinLimit("hello", 100)).isTrue();
        verify(delegate, times(1)).isWithinLimit("hello", 100);
    }

    @Test
    void getProviderName_appendsResilientSuffix() {
        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null);

        assertThat(client.getProviderName()).isEqualTo("openai-resilient");
    }

    @Test
    void completeWithSystem_delegatesViaComplete() {
        when(delegate.complete(any(LLMRequest.class))).thenReturn(SUCCESS_RESPONSE);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null);

        LLMResponse result = client.completeWithSystem("system prompt", "user message");

        assertThat(result.getContent()).isEqualTo("ok");
        verify(delegate, times(1)).complete(any(LLMRequest.class));
    }

    @Test
    void complete_throwsTokenLimitExceeded_neverCallsDelegate() {
        TokenLimitExceededException limitEx = new TokenLimitExceededException(960, 1000);
        doThrow(limitEx).when(delegate).truncateIfNeeded(REQUEST.getUserMessage(), 1000);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null, 1000);

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(TokenLimitExceededException.class);

        verify(delegate, never()).complete(any());
    }

    @Test
    void complete_usesTruncatedMessage_whenTruncateIfNeededReturnsShortened() {
        String truncated = "shortened... [truncated]";
        // Use doReturn to avoid invoking the default method body during stubbing setup
        doReturn(truncated).when(delegate).truncateIfNeeded(REQUEST.getUserMessage(), 1000);
        when(delegate.complete(any(LLMRequest.class))).thenReturn(SUCCESS_RESPONSE);

        ResilientLLMClient client = new ResilientLLMClient(delegate, fastRetry(), null, 1000);
        client.complete(REQUEST);

        ArgumentCaptor<LLMRequest> captor = ArgumentCaptor.forClass(LLMRequest.class);
        verify(delegate).complete(captor.capture());
        assertThat(captor.getValue().getUserMessage()).isEqualTo(truncated);
    }
}
