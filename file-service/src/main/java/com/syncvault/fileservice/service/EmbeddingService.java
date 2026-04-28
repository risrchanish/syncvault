package com.syncvault.fileservice.service;

import com.syncvault.aisdk.client.LLMClient;
import com.syncvault.fileservice.repository.FileEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final LLMClient llmClient;
    private final FileEmbeddingRepository embeddingRepository;

    public void generateAndStore(UUID fileId, UUID userId, String text) {
        try {
            var response = llmClient.embed(text);
            embeddingRepository.save(fileId, userId, response.getEmbedding());
            log.debug("Embedding stored for file {}", fileId);
        } catch (Exception e) {
            log.warn("Embedding skipped for file {}: {}", fileId, e.getMessage());
        }
    }
}
