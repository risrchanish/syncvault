package com.syncvault.fileservice.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncvault.aisdk.client.LLMClient;
import com.syncvault.aisdk.model.LLMRequest;
import com.syncvault.fileservice.entity.FileMetadata;
import com.syncvault.fileservice.exception.FileNotFoundException;
import com.syncvault.fileservice.repository.FileMetadataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentSummarizationService {

    private static final String SUMMARY_SYSTEM_PROMPT =
            "You are a document summarizer. Return a concise 2-3 sentence summary. " +
            "Focus on key topics. Be factual and neutral.";

    private static final String DESCRIPTION_SYSTEM_PROMPT =
            "You are a file cataloger. Return JSON with two fields: " +
            "description (one sentence, max 100 words) and tags (array of 3-5 keywords). " +
            "Return only valid JSON, no markdown fences.";

    private final LLMClient llmClient;
    private final DocumentTextExtractor textExtractor;
    private final FileMetadataRepository fileRepo;
    private final ObjectMapper objectMapper;

    public String summarize(MultipartFile file) {
        String text = textExtractor.extractText(file);
        if (text.isBlank()) return null;
        return callSummaryLlm(text, file.getOriginalFilename());
    }

    public AiDescriptionResult generateDescription(String extractedText, String fileName) {
        if (extractedText == null || extractedText.isBlank()) {
            return AiDescriptionResult.empty();
        }
        try {
            String response = llmClient.complete(LLMRequest.builder()
                    .systemPrompt(DESCRIPTION_SYSTEM_PROMPT)
                    .userMessage("Analyze and return description + tags:\n\n" + extractedText)
                    .maxTokens(150)
                    .temperature(0.2)
                    .build()).getContent();
            return parseDescriptionJson(response);
        } catch (Exception e) {
            log.warn("Description generation failed for '{}': {}", fileName, e.getMessage());
            return AiDescriptionResult.empty();
        }
    }

    @Transactional
    public void processAndPersist(UUID fileId, MultipartFile file, String fileName) {
        String text = textExtractor.extractText(file);
        persistWithText(fileId, text, fileName);
    }

    @Transactional
    public void processAndPersist(UUID fileId, byte[] content, String mimeType, String fileName) {
        String text = textExtractor.extractText(content, mimeType, fileName);
        persistWithText(fileId, text, fileName);
    }

    private void persistWithText(UUID fileId, String text, String fileName) {
        FileMetadata metadata = fileRepo.findByIdAndDeletedFalse(fileId)
                .orElseThrow(() -> new FileNotFoundException("File not found: " + fileId));

        String summary = callSummaryLlm(text, fileName);
        AiDescriptionResult descResult = generateDescription(text, fileName);

        metadata.setAiSummary(summary);
        metadata.setAiDescription(descResult.description());
        metadata.setAiTags(descResult.tags().isEmpty() ? null : String.join(", ", descResult.tags()));
        metadata.setAiProcessedAt(LocalDateTime.now());
        fileRepo.save(metadata);

        log.debug("AI processing complete for file {}", fileId);
    }

    private String callSummaryLlm(String text, String fileName) {
        if (text == null || text.isBlank()) return null;
        try {
            return llmClient.complete(LLMRequest.builder()
                    .systemPrompt(SUMMARY_SYSTEM_PROMPT)
                    .userMessage("Summarize this document:\n\n" + text)
                    .maxTokens(300)
                    .temperature(0.1)
                    .build()).getContent();
        } catch (Exception e) {
            log.warn("Summary LLM failed for '{}': {}", fileName, e.getMessage());
            return null;
        }
    }

    private AiDescriptionResult parseDescriptionJson(String raw) {
        try {
            String json = raw.trim();
            if (json.startsWith("```")) {
                int start = json.indexOf('\n') + 1;
                int end = json.lastIndexOf("```");
                json = json.substring(start, end).trim();
            }
            Map<String, Object> map = objectMapper.readValue(json, new TypeReference<>() {});
            String description = (String) map.get("description");
            Object tagsRaw = map.get("tags");
            List<String> tags = List.of();
            if (tagsRaw instanceof List<?> rawList) {
                tags = rawList.stream()
                        .filter(t -> t instanceof String)
                        .map(t -> (String) t)
                        .toList();
            }
            return new AiDescriptionResult(description, tags);
        } catch (Exception e) {
            log.warn("JSON parse failed for description response: {}", e.getMessage());
            return AiDescriptionResult.empty();
        }
    }
}
