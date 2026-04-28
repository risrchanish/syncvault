package com.syncvault.fileservice.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DocumentTextExtractorTest {

    private DocumentTextExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new DocumentTextExtractor();
    }

    @Test
    void extractText_returnContent_forPlainTextFile() {
        String content = "Hello SyncVault. This is a plain-text document.";
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extractText(file);

        assertThat(result).contains("Hello SyncVault");
    }

    @Test
    void extractText_trimsLeadingAndTrailingWhitespace() {
        String content = "\n\n  whitespace document  \n\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "space.txt", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));

        String result = extractor.extractText(file);

        assertThat(result).doesNotStartWith("\n");
        assertThat(result).doesNotEndWith("\n");
    }

    @Test
    void extractText_returnsEmptyString_onIoException() throws IOException {
        MultipartFile badFile = mock(MultipartFile.class);
        when(badFile.getInputStream()).thenThrow(new IOException("simulated disk failure"));
        when(badFile.getOriginalFilename()).thenReturn("broken.pdf");

        String result = extractor.extractText(badFile);

        assertThat(result).isEmpty();
    }

    @Test
    void extractText_returnsEmptyString_forEmptyFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        String result = extractor.extractText(file);

        assertThat(result).isEmpty();
    }

    @Test
    void extractText_handlesNullContentType_gracefully() {
        String content = "content without mime type";
        MockMultipartFile file = new MockMultipartFile(
                "file", "unknown", null,
                content.getBytes(StandardCharsets.UTF_8));

        // Should not throw; Tika will auto-detect or return plain text
        String result = extractor.extractText(file);

        assertThat(result).isNotNull();
    }

    @Test
    void extractText_handlesNullOriginalFilename_gracefully() {
        String content = "content without filename";
        // MockMultipartFile with name "" simulates null original filename path
        MockMultipartFile file = new MockMultipartFile(
                "file", "", "text/plain",
                content.getBytes(StandardCharsets.UTF_8));

        assertThat(extractor.extractText(file)).isNotNull();
    }

    // ── byte[] overload ──────────────────────────────────────────────────────

    @Test
    void extractText_byteArray_returnsText() {
        byte[] content = "Hello byte world.".getBytes(StandardCharsets.UTF_8);

        String result = extractor.extractText(content, "text/plain", "doc.txt");

        assertThat(result).contains("Hello byte world");
    }

    @Test
    void extractText_byteArray_emptyContent_returnsEmpty() {
        String result = extractor.extractText(new byte[0], "text/plain", "empty.txt");

        assertThat(result).isEmpty();
    }

    @Test
    void extractText_returnsEmptyString_onParseException() throws IOException {
        // Simulate a file whose InputStream throws after being opened
        MultipartFile badFile = mock(MultipartFile.class);
        InputStream brokenStream = mock(InputStream.class);
        when(brokenStream.read()).thenThrow(new IOException("stream broken mid-read"));
        when(brokenStream.read(any(byte[].class))).thenThrow(new IOException("stream broken mid-read"));
        when(brokenStream.read(any(byte[].class), anyInt(), anyInt()))
                .thenThrow(new IOException("stream broken mid-read"));
        when(badFile.getInputStream()).thenReturn(brokenStream);
        when(badFile.getOriginalFilename()).thenReturn("corrupt.pdf");
        when(badFile.getContentType()).thenReturn("application/pdf");

        String result = extractor.extractText(badFile);

        assertThat(result).isEmpty();
    }
}
