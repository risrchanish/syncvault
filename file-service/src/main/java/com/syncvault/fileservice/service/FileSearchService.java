package com.syncvault.fileservice.service;

import com.syncvault.aisdk.client.LLMClient;
import com.syncvault.aisdk.model.EmbeddingResponse;
import com.syncvault.fileservice.dto.response.FileSearchResult;
import com.syncvault.fileservice.entity.FileMetadata;
import com.syncvault.fileservice.repository.EmbeddingSearchResult;
import com.syncvault.fileservice.repository.FileEmbeddingRepository;
import com.syncvault.fileservice.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileSearchService {

    private final LLMClient llmClient;
    private final FileEmbeddingRepository embeddingRepository;
    private final FileMetadataRepository fileRepo;

    @Transactional(readOnly = true)
    public List<FileSearchResult> search(UUID userId, String query, int limit) {
        EmbeddingResponse queryEmbedding;
        try {
            queryEmbedding = llmClient.embed(query);
        } catch (Exception e) {
            log.warn("Search unavailable — embedding failed: {}", e.getMessage());
            return List.of();
        }
        List<EmbeddingSearchResult> candidates =
                embeddingRepository.findSimilar(userId, queryEmbedding.getEmbedding(), limit);

        if (candidates.isEmpty()) {
            return List.of();
        }

        List<UUID> fileIds = candidates.stream().map(EmbeddingSearchResult::fileId).toList();
        Map<UUID, FileMetadata> metaMap = fileRepo.findAllById(fileIds).stream()
                .collect(Collectors.toMap(FileMetadata::getId, m -> m));

        return candidates.stream()
                .filter(c -> metaMap.containsKey(c.fileId()))
                .map(c -> {
                    FileMetadata meta = metaMap.get(c.fileId());
                    return FileSearchResult.builder()
                            .fileId(meta.getId().toString())
                            .fileName(meta.getFileName())
                            .fileSize(meta.getFileSizeBytes())
                            .relevanceScore(c.score())
                            .build();
                })
                .toList();
    }
}
