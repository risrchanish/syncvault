package com.syncvault.fileservice.service;

import com.syncvault.fileservice.client.UserStorageClient;
import com.syncvault.fileservice.dto.response.*;
import java.time.LocalDateTime;
import com.syncvault.fileservice.entity.FileMetadata;
import com.syncvault.fileservice.entity.FileVersion;
import com.syncvault.fileservice.exception.FileForbiddenException;
import com.syncvault.fileservice.exception.FileNotFoundException;
import com.syncvault.fileservice.exception.StorageLimitExceededException;
import com.syncvault.fileservice.exception.VersionNotFoundException;
import com.syncvault.fileservice.kafka.FileEventProducer;
import com.syncvault.fileservice.repository.FileMetadataRepository;
import com.syncvault.fileservice.repository.FileVersionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock FileMetadataRepository fileRepo;
    @Mock FileVersionRepository versionRepo;
    @Mock S3Service s3Service;
    @Mock FileEventProducer eventProducer;
    @Mock UserStorageClient storageClient;
    @Mock DocumentSummarizationService summarizationService;
    @Mock EmbeddingService embeddingService;

    @InjectMocks FileService fileService;

    UUID userId;
    UUID fileId;
    FileMetadata sampleFile;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        fileId = UUID.randomUUID();
        sampleFile = FileMetadata.builder()
                .id(fileId)
                .userId(userId)
                .fileName("test.txt")
                .fileSizeBytes(1024L)
                .s3Key(userId + "/" + fileId + "/1/test.txt")
                .mimeType("text/plain")
                .versionNumber(1)
                .build();
    }

    // ── Upload ──────────────────────────────────────────────────────────────

    @Test
    void uploadFile_success() throws IOException {
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "test.txt", "text/plain", "hello".getBytes());

        when(fileRepo.sumFileSizeByUserId(userId)).thenReturn(0L);
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(s3Service.generatePresignedUrl(anyString())).thenReturn("https://presigned.url");

        UploadResponse resp = fileService.uploadFile(multipart, userId);

        assertThat(resp.getFileName()).isEqualTo("test.txt");
        assertThat(resp.getVersionNumber()).isEqualTo(1);
        assertThat(resp.getS3Url()).isEqualTo("https://presigned.url");
        verify(s3Service).uploadFile(anyString(), any(), eq("text/plain"));
        verify(eventProducer).publishUploaded(any());
        verify(storageClient).addStorage(eq(userId), eq(5L));
        verify(summarizationService, timeout(1000)).processAndPersist(any(UUID.class), eq(multipart), eq("test.txt"));
        verify(embeddingService, timeout(1000)).generateAndStore(any(UUID.class), eq(userId), anyString());
    }

    @Test
    void uploadFile_storageLimitExceeded_throws() {
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "big.bin", "application/octet-stream", new byte[1024]);

        long fiveGb = 5L * 1024 * 1024 * 1024;
        when(fileRepo.sumFileSizeByUserId(userId)).thenReturn(fiveGb);

        assertThatThrownBy(() -> fileService.uploadFile(multipart, userId))
                .isInstanceOf(StorageLimitExceededException.class);

        verify(s3Service, never()).uploadFile(any(), any(), any());
    }

    // ── Get file ────────────────────────────────────────────────────────────

    @Test
    void getFile_success() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));
        when(s3Service.generatePresignedUrl(anyString())).thenReturn("https://presigned.url");

        FileInfoResponse resp = fileService.getFile(fileId, userId);

        assertThat(resp.getFileId()).isEqualTo(fileId.toString());
        assertThat(resp.getDownloadUrl()).isEqualTo("https://presigned.url");
    }

    @Test
    void getFile_notFound_throws() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.getFile(fileId, userId))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void getFile_wrongUser_throwsForbidden() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));

        assertThatThrownBy(() -> fileService.getFile(fileId, UUID.randomUUID()))
                .isInstanceOf(FileForbiddenException.class);
    }

    // ── List user files ─────────────────────────────────────────────────────

    @Test
    void getUserFiles_success() {
        when(fileRepo.findByUserIdAndDeletedFalse(userId)).thenReturn(List.of(sampleFile));

        List<FileListItemResponse> result = fileService.getUserFiles(userId, userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFileName()).isEqualTo("test.txt");
    }

    @Test
    void getUserFiles_differentUser_throwsForbidden() {
        assertThatThrownBy(() -> fileService.getUserFiles(userId, UUID.randomUUID()))
                .isInstanceOf(FileForbiddenException.class);
    }

    // ── Update ──────────────────────────────────────────────────────────────

    @Test
    void updateFile_safeUpdate_incrementsVersion() throws IOException {
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "test.txt", "text/plain", "updated".getBytes());

        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));
        when(fileRepo.sumFileSizeByUserId(userId)).thenReturn(0L);
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Object result = fileService.updateFile(fileId, multipart, 1, userId);

        assertThat(result).isInstanceOf(UpdateResponse.class);
        UpdateResponse resp = (UpdateResponse) result;
        assertThat(resp.getNewVersionNumber()).isEqualTo(2);
        verify(eventProducer).publishUpdated(any());
    }

    @Test
    void updateFile_conflict_returnsConflictResponse() throws IOException {
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "test.txt", "text/plain", "conflict".getBytes());

        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));
        when(fileRepo.sumFileSizeByUserId(userId)).thenReturn(0L);
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // baseVersion=0 != currentVersion=1 → conflict
        Object result = fileService.updateFile(fileId, multipart, 0, userId);

        assertThat(result).isInstanceOf(ConflictResponse.class);
        ConflictResponse resp = (ConflictResponse) result;
        assertThat(resp.getMessage()).isEqualTo("Conflict detected");
        verify(eventProducer).publishConflict(any());
        // original file versionNumber must NOT have changed
        assertThat(sampleFile.getVersionNumber()).isEqualTo(1);
    }

    @Test
    void updateFile_nullBaseVersion_treatedAsConflict() throws IOException {
        MockMultipartFile multipart = new MockMultipartFile(
                "file", "test.txt", "text/plain", "data".getBytes());

        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));
        when(fileRepo.sumFileSizeByUserId(userId)).thenReturn(0L);
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Object result = fileService.updateFile(fileId, multipart, null, userId);

        assertThat(result).isInstanceOf(ConflictResponse.class);
    }

    // ── Delete ──────────────────────────────────────────────────────────────

    @Test
    void deleteFile_success_setsDeletedFlag() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MessageResponse resp = fileService.deleteFile(fileId, userId);

        assertThat(resp.getMessage()).isEqualTo("File moved to trash");
        assertThat(sampleFile.isDeleted()).isTrue();
        assertThat(sampleFile.getDeletedAt()).isNotNull();
        verify(eventProducer).publishDeleted(any());
    }

    @Test
    void deleteFile_wrongUser_throwsForbidden() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));

        assertThatThrownBy(() -> fileService.deleteFile(fileId, UUID.randomUUID()))
                .isInstanceOf(FileForbiddenException.class);
    }

    // ── Versions ─────────────────────────────────────────────────────────────

    @Test
    void getVersions_success() {
        FileVersion v1 = FileVersion.builder()
                .id(UUID.randomUUID()).fileId(fileId).versionNumber(1)
                .s3Key("key/1").fileSizeBytes(100L).createdBy(userId).build();

        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));
        when(versionRepo.findByFileIdOrderByVersionNumberAsc(fileId)).thenReturn(List.of(v1));
        when(s3Service.generatePresignedUrl(anyString())).thenReturn("https://url");

        List<VersionListItemResponse> versions = fileService.getVersions(fileId, userId);

        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).getVersionNumber()).isEqualTo(1);
    }

    // ── Restore ──────────────────────────────────────────────────────────────

    @Test
    void restoreVersion_success() {
        FileVersion v1 = FileVersion.builder()
                .id(UUID.randomUUID()).fileId(fileId).versionNumber(1)
                .s3Key("key/1").fileSizeBytes(100L).createdBy(userId).build();

        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));
        when(versionRepo.findByFileIdAndVersionNumber(fileId, 1)).thenReturn(Optional.of(v1));
        when(versionRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(fileRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RestoreResponse resp = fileService.restoreVersion(fileId, 1, userId);

        assertThat(resp.getRestoredVersion()).isEqualTo(2);
        assertThat(resp.getFileId()).isEqualTo(fileId.toString());

        ArgumentCaptor<FileVersion> captor = ArgumentCaptor.forClass(FileVersion.class);
        verify(versionRepo).save(captor.capture());
        assertThat(captor.getValue().getVersionNumber()).isEqualTo(2);
        assertThat(captor.getValue().getS3Key()).isEqualTo("key/1");
    }

    // ── retriggerSummarization ────────────────────────────────────────────────

    @Test
    void retriggerSummarization_success_returnsAiSummaryResponse() {
        FileMetadata enrichedFile = FileMetadata.builder()
                .id(fileId).userId(userId).fileName("test.txt")
                .fileSizeBytes(1024L).s3Key(userId + "/" + fileId + "/1/test.txt")
                .mimeType("text/plain")
                .aiSummary("Regenerated summary").aiDescription("New description")
                .aiTags("tag1, tag2").aiProcessedAt(LocalDateTime.now())
                .build();

        when(fileRepo.findByIdAndDeletedFalse(fileId))
                .thenReturn(Optional.of(sampleFile))
                .thenReturn(Optional.of(enrichedFile));
        when(s3Service.downloadFile(sampleFile.getS3Key())).thenReturn("content".getBytes());

        AiSummaryResponse resp = fileService.retriggerSummarization(fileId, userId);

        assertThat(resp.getFileId()).isEqualTo(fileId.toString());
        assertThat(resp.getFileName()).isEqualTo("test.txt");
        assertThat(resp.getAiSummary()).isEqualTo("Regenerated summary");
        verify(s3Service).downloadFile(sampleFile.getS3Key());
        verify(summarizationService).processAndPersist(
                eq(fileId), any(byte[].class), eq("text/plain"), eq("test.txt"));
    }

    @Test
    void retriggerSummarization_fileNotFound_throws() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.retriggerSummarization(fileId, userId))
                .isInstanceOf(FileNotFoundException.class);
        verify(s3Service, never()).downloadFile(any());
    }

    @Test
    void retriggerSummarization_wrongUser_throwsForbidden() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));

        assertThatThrownBy(() -> fileService.retriggerSummarization(fileId, UUID.randomUUID()))
                .isInstanceOf(FileForbiddenException.class);
        verify(s3Service, never()).downloadFile(any());
    }

    // ── getSummary ────────────────────────────────────────────────────────────

    @Test
    void getSummary_success_returnsAiFields() {
        FileMetadata fileWithSummary = FileMetadata.builder()
                .id(fileId).userId(userId).fileName("test.txt").fileSizeBytes(1024L)
                .s3Key("key").mimeType("text/plain")
                .aiSummary("Stored summary").aiDescription("Stored description")
                .aiTags("a, b").aiProcessedAt(LocalDateTime.now())
                .build();
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(fileWithSummary));

        AiSummaryResponse resp = fileService.getSummary(fileId, userId);

        assertThat(resp.getFileId()).isEqualTo(fileId.toString());
        assertThat(resp.getAiSummary()).isEqualTo("Stored summary");
        assertThat(resp.getAiDescription()).isEqualTo("Stored description");
        assertThat(resp.getAiTags()).isEqualTo("a, b");
    }

    @Test
    void getSummary_fileNotFound_throws() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.getSummary(fileId, userId))
                .isInstanceOf(FileNotFoundException.class);
    }

    @Test
    void getSummary_wrongUser_throwsForbidden() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));

        assertThatThrownBy(() -> fileService.getSummary(fileId, UUID.randomUUID()))
                .isInstanceOf(FileForbiddenException.class);
    }

    @Test
    void restoreVersion_versionNotFound_throws() {
        when(fileRepo.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(sampleFile));
        when(versionRepo.findByFileIdAndVersionNumber(fileId, 99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> fileService.restoreVersion(fileId, 99, userId))
                .isInstanceOf(VersionNotFoundException.class);
    }
}
