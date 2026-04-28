package com.syncvault.fileservice.service;

import com.syncvault.aisdk.client.LLMClient;
import com.syncvault.aisdk.model.EmbeddingResponse;
import com.syncvault.fileservice.dto.response.FileSearchResult;
import com.syncvault.fileservice.entity.FileMetadata;
import com.syncvault.fileservice.repository.EmbeddingSearchResult;
import com.syncvault.fileservice.repository.FileEmbeddingRepository;
import com.syncvault.fileservice.repository.FileMetadataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileSearchServiceTest {

    @Mock LLMClient llmClient;
    @Mock FileEmbeddingRepository embeddingRepository;
    @Mock FileMetadataRepository fileRepo;

    @InjectMocks FileSearchService fileSearchService;

    UUID userId = UUID.randomUUID();
    List<Double> queryVector = List.of(0.1, 0.2, 0.3);

    private EmbeddingResponse embeddingResponse() {
        return EmbeddingResponse.builder()
                .embedding(queryVector)
                .inputTokens(3)
                .modelUsed("text-embedding-3-small")
                .build();
    }

    @Test
    void search_returnsRankedResults() {
        UUID fileId1 = UUID.randomUUID();
        UUID fileId2 = UUID.randomUUID();

        when(llmClient.embed("tax document")).thenReturn(embeddingResponse());
        when(embeddingRepository.findSimilar(eq(userId), eq(queryVector), eq(5)))
                .thenReturn(List.of(
                        new EmbeddingSearchResult(fileId1, 0.95),
                        new EmbeddingSearchResult(fileId2, 0.80)
                ));

        FileMetadata meta1 = FileMetadata.builder().id(fileId1).userId(userId)
                .fileName("taxes-2024.pdf").fileSizeBytes(2048L).s3Key("k1").build();
        FileMetadata meta2 = FileMetadata.builder().id(fileId2).userId(userId)
                .fileName("tax-summary.docx").fileSizeBytes(1024L).s3Key("k2").build();
        when(fileRepo.findAllById(List.of(fileId1, fileId2))).thenReturn(List.of(meta1, meta2));

        List<FileSearchResult> results = fileSearchService.search(userId, "tax document", 5);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getFileId()).isEqualTo(fileId1.toString());
        assertThat(results.get(0).getFileName()).isEqualTo("taxes-2024.pdf");
        assertThat(results.get(0).getRelevanceScore()).isEqualTo(0.95);
        assertThat(results.get(1).getRelevanceScore()).isEqualTo(0.80);
        verify(llmClient).embed("tax document");
    }

    @Test
    void search_noEmbeddingsFound_returnsEmpty() {
        when(llmClient.embed(any())).thenReturn(embeddingResponse());
        when(embeddingRepository.findSimilar(any(), any(), anyInt())).thenReturn(List.of());

        List<FileSearchResult> results = fileSearchService.search(userId, "nonexistent", 10);

        assertThat(results).isEmpty();
        verify(fileRepo, never()).findAllById(any());
    }

    @Test
    void search_metadataNotFound_filtersOutMissingFiles() {
        UUID knownId = UUID.randomUUID();
        UUID unknownId = UUID.randomUUID();

        when(llmClient.embed(any())).thenReturn(embeddingResponse());
        when(embeddingRepository.findSimilar(any(), any(), anyInt()))
                .thenReturn(List.of(
                        new EmbeddingSearchResult(knownId, 0.90),
                        new EmbeddingSearchResult(unknownId, 0.75)
                ));

        FileMetadata meta = FileMetadata.builder().id(knownId).userId(userId)
                .fileName("report.pdf").fileSizeBytes(512L).s3Key("k").build();
        when(fileRepo.findAllById(List.of(knownId, unknownId))).thenReturn(List.of(meta));

        List<FileSearchResult> results = fileSearchService.search(userId, "report", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getFileId()).isEqualTo(knownId.toString());
    }
}
