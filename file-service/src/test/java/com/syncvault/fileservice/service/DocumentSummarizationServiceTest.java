package com.syncvault.fileservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncvault.aisdk.client.LLMClient;
import com.syncvault.aisdk.model.LLMResponse;
import com.syncvault.fileservice.entity.FileMetadata;
import com.syncvault.fileservice.exception.FileNotFoundException;
import com.syncvault.fileservice.repository.FileMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentSummarizationServiceTest {

    @Mock LLMClient llmClient;
    @Mock DocumentTextExtractor textExtractor;
    @Mock FileMetadataRepository fileRepo;

    DocumentSummarizationService service;

    UUID fileId;
    MockMultipartFile multipart;
    FileMetadata sampleMetadata;

    @BeforeEach
    void setUp() {
        service = new DocumentSummarizationService(llmClient, textExtractor, fileRepo, new ObjectMapper());

        fileId = UUID.randomUUID();
        multipart = new MockMultipartFile("file", "doc.txt", "text/plain", "content".getBytes());
        sampleMetadata = FileMetadata.builder()
                .id(fileId)
                .userId(UUID.randomUUID())
                .fileName("doc.txt")
                .fileSizeBytes(100L)
                .s3Key("users/key/doc.txt")
                .build();
    }

    // ── summarize ────────────────────────────────────────────────────────────

    @Test
    void summarize_returnsNull_whenExtractedTextIsBlank() {
        when(textExtractor.extractText(multipart)).thenReturn("");

        assertThat(service.summarize(multipart)).isNull();
        verifyNoInteractions(llmClient);
    }

    @Test
    void summarize_callsLlmAndReturnsSummary() {
        when(textExtractor.extractText(multipart)).thenReturn("Document text here.");
        when(llmClient.complete(any())).thenReturn(llmResponse("This is the summary."));

        String result = service.summarize(multipart);

        assertThat(result).isEqualTo("This is the summary.");
    }

    @Test
    void summarize_returnsNull_onLlmException() {
        when(textExtractor.extractText(multipart)).thenReturn("Document text here.");
        when(llmClient.complete(any())).thenThrow(new RuntimeException("LLM offline"));

        assertThat(service.summarize(multipart)).isNull();
    }

    // ── generateDescription ──────────────────────────────────────────────────

    @Test
    void generateDescription_returnsEmpty_whenTextIsBlank() {
        AiDescriptionResult result = service.generateDescription("", "file.txt");

        assertThat(result.description()).isNull();
        assertThat(result.tags()).isEmpty();
        verifyNoInteractions(llmClient);
    }

    @Test
    void generateDescription_parsesJsonCorrectly() {
        String json = "{\"description\": \"A financial report.\", \"tags\": [\"finance\", \"report\", \"2024\"]}";
        when(llmClient.complete(any())).thenReturn(llmResponse(json));

        AiDescriptionResult result = service.generateDescription("Report content", "report.pdf");

        assertThat(result.description()).isEqualTo("A financial report.");
        assertThat(result.tags()).containsExactly("finance", "report", "2024");
    }

    @Test
    void generateDescription_stripsMarkdownFences() {
        String fenced = "```json\n{\"description\": \"Meeting notes.\", \"tags\": [\"meeting\", \"notes\"]}\n```";
        when(llmClient.complete(any())).thenReturn(llmResponse(fenced));

        AiDescriptionResult result = service.generateDescription("Meeting content", "notes.txt");

        assertThat(result.description()).isEqualTo("Meeting notes.");
        assertThat(result.tags()).containsExactly("meeting", "notes");
    }

    @Test
    void generateDescription_returnsEmpty_onMalformedJson() {
        when(llmClient.complete(any())).thenReturn(llmResponse("not valid json at all"));

        AiDescriptionResult result = service.generateDescription("some text", "file.txt");

        assertThat(result.description()).isNull();
        assertThat(result.tags()).isEmpty();
    }

    @Test
    void generateDescription_returnsEmpty_onLlmException() {
        when(llmClient.complete(any())).thenThrow(new RuntimeException("timeout"));

        AiDescriptionResult result = service.generateDescription("some text", "file.txt");

        assertThat(result.description()).isNull();
        assertThat(result.tags()).isEmpty();
    }

    // ── processAndPersist ────────────────────────────────────────────────────

    @Test
    void processAndPersist_savesAllAiFields() {
        String descJson = "{\"description\": \"A tax document.\", \"tags\": [\"tax\", \"legal\"]}";
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleMetadata));
        when(textExtractor.extractText(multipart)).thenReturn("Tax-related document content.");
        when(llmClient.complete(any()))
                .thenReturn(llmResponse("Summary of a tax document."))
                .thenReturn(llmResponse(descJson));

        service.processAndPersist(fileId, multipart, "doc.txt");

        ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileRepo).save(captor.capture());

        FileMetadata saved = captor.getValue();
        assertThat(saved.getAiSummary()).isEqualTo("Summary of a tax document.");
        assertThat(saved.getAiDescription()).isEqualTo("A tax document.");
        assertThat(saved.getAiTags()).isEqualTo("tax, legal");
        assertThat(saved.getAiProcessedAt()).isNotNull();
    }

    @Test
    void processAndPersist_setsAiProcessedAt_evenWhenBothLlmsFail() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleMetadata));
        when(textExtractor.extractText(multipart)).thenReturn("Some document text.");
        when(llmClient.complete(any())).thenThrow(new RuntimeException("LLM down"));

        service.processAndPersist(fileId, multipart, "doc.txt");

        ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileRepo).save(captor.capture());

        FileMetadata saved = captor.getValue();
        assertThat(saved.getAiProcessedAt()).isNotNull();
        assertThat(saved.getAiSummary()).isNull();
        assertThat(saved.getAiDescription()).isNull();
    }

    @Test
    void processAndPersist_byteArray_savesAllAiFields() {
        String descJson = "{\"description\": \"A byte document.\", \"tags\": [\"byte\", \"test\"]}";
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleMetadata));
        when(textExtractor.extractText(any(byte[].class), any(), any())).thenReturn("Byte text.");
        when(llmClient.complete(any()))
                .thenReturn(llmResponse("Byte summary."))
                .thenReturn(llmResponse(descJson));

        service.processAndPersist(fileId, "data".getBytes(), "text/plain", "doc.txt");

        ArgumentCaptor<FileMetadata> captor = ArgumentCaptor.forClass(FileMetadata.class);
        verify(fileRepo).save(captor.capture());
        assertThat(captor.getValue().getAiSummary()).isEqualTo("Byte summary.");
        assertThat(captor.getValue().getAiDescription()).isEqualTo("A byte document.");
        assertThat(captor.getValue().getAiTags()).isEqualTo("byte, test");
    }

    @Test
    void processAndPersist_throwsFileNotFound_whenFileDoesNotExist() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processAndPersist(fileId, multipart, "doc.txt"))
                .isInstanceOf(FileNotFoundException.class);

        verify(fileRepo, never()).save(any());
    }

    // ── helper ───────────────────────────────────────────────────────────────

    private LLMResponse llmResponse(String content) {
        return LLMResponse.builder()
                .content(content)
                .inputTokens(10)
                .outputTokens(20)
                .totalTokens(30)
                .providerName("openai")
                .modelUsed("gpt-4o-mini")
                .finishReason("stop")
                .build();
    }
}
