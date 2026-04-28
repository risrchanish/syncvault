package com.syncvault.fileservice.service;

import com.syncvault.aisdk.client.LLMClient;
import com.syncvault.aisdk.model.EmbeddingResponse;
import com.syncvault.fileservice.repository.FileEmbeddingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmbeddingServiceTest {

    @Mock LLMClient llmClient;
    @Mock FileEmbeddingRepository embeddingRepository;

    @InjectMocks EmbeddingService embeddingService;

    UUID fileId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    @Test
    void generateAndStore_success_storesEmbedding() {
        List<Double> vector = List.of(0.1, 0.2, 0.3);
        when(llmClient.embed("quarterly report finance"))
                .thenReturn(EmbeddingResponse.builder()
                        .embedding(vector)
                        .inputTokens(4)
                        .modelUsed("text-embedding-3-small")
                        .build());

        embeddingService.generateAndStore(fileId, userId, "quarterly report finance");

        verify(llmClient).embed("quarterly report finance");
        verify(embeddingRepository).save(eq(fileId), eq(userId), eq(vector));
    }

    @Test
    void generateAndStore_embedThrows_logsAndSkips() {
        when(llmClient.embed(any())).thenThrow(new RuntimeException("OpenAI unavailable"));

        embeddingService.generateAndStore(fileId, userId, "some text");

        verify(llmClient).embed("some text");
        verify(embeddingRepository, never()).save(any(), any(), any());
    }

    @Test
    void generateAndStore_saveThrows_logsAndSkips() {
        List<Double> vector = List.of(0.5, 0.6);
        when(llmClient.embed(any())).thenReturn(EmbeddingResponse.builder()
                .embedding(vector).inputTokens(2).modelUsed("text-embedding-3-small").build());
        doThrow(new RuntimeException("DB error")).when(embeddingRepository).save(any(), any(), any());

        embeddingService.generateAndStore(fileId, userId, "text");

        verify(embeddingRepository).save(eq(fileId), eq(userId), eq(vector));
    }
}
