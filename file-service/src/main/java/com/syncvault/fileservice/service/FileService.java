package com.syncvault.fileservice.service;

import com.syncvault.fileservice.client.UserStorageClient;
import com.syncvault.fileservice.dto.response.*;
import com.syncvault.fileservice.entity.FileMetadata;
import com.syncvault.fileservice.entity.FileVersion;
import com.syncvault.fileservice.exception.FileForbiddenException;
import com.syncvault.fileservice.exception.FileNotFoundException;
import com.syncvault.fileservice.exception.StorageLimitExceededException;
import com.syncvault.fileservice.exception.VersionNotFoundException;
import com.syncvault.fileservice.kafka.FileEvent;
import com.syncvault.fileservice.kafka.FileEventProducer;
import com.syncvault.fileservice.repository.FileMetadataRepository;
import com.syncvault.fileservice.repository.FileVersionRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    // Phase 2.5: replace with paymentServiceClient.getStorageLimit(userId)
    private static final long STORAGE_LIMIT_BYTES = 5L * 1024 * 1024 * 1024;

    private final FileMetadataRepository fileRepo;
    private final FileVersionRepository versionRepo;
    private final S3Service s3Service;
    private final FileEventProducer eventProducer;
    private final UserStorageClient storageClient;
    private final DocumentSummarizationService summarizationService;
    private final EmbeddingService embeddingService;

    public UploadResponse uploadFile(MultipartFile file, UUID userId) throws IOException {
        long currentUsage = fileRepo.sumFileSizeByUserId(userId);
        if (currentUsage + file.getSize() > STORAGE_LIMIT_BYTES) {
            throw new StorageLimitExceededException("Storage limit of 5 GB exceeded");
        }

        UUID fileId = UUID.randomUUID();
        String s3Key = buildS3Key(userId, fileId, 1, file.getOriginalFilename());

        s3Service.uploadFile(s3Key, file.getBytes(), file.getContentType());

        FileMetadata metadata = FileMetadata.builder()
                .id(fileId)
                .userId(userId)
                .fileName(file.getOriginalFilename())
                .fileSizeBytes(file.getSize())
                .s3Key(s3Key)
                .mimeType(file.getContentType())
                .versionNumber(1)
                .build();
        fileRepo.save(metadata);

        versionRepo.save(FileVersion.builder()
                .fileId(fileId)
                .versionNumber(1)
                .s3Key(s3Key)
                .fileSizeBytes(file.getSize())
                .createdBy(userId)
                .build());

        storageClient.addStorage(userId, file.getSize());

        eventProducer.publishUploaded(buildEvent("file.uploaded", userId, fileId,
                file.getOriginalFilename(), file.getSize()));

        String fileName = file.getOriginalFilename();
        Thread.ofVirtual().name("ai-summary-" + fileId).start(() -> {
            try {
                summarizationService.processAndPersist(fileId, file, fileName);
            } catch (Exception e) {
                log.warn("AI summarization failed for file {}: {}", fileId, e.getMessage());
            }
        });
        Thread.ofVirtual().name("ai-embed-" + fileId).start(() -> {
            embeddingService.generateAndStore(fileId, userId, resolveEmbedText(fileId, fileName));
        });

        return UploadResponse.builder()
                .fileId(fileId.toString())
                .fileName(file.getOriginalFilename())
                .fileSize(file.getSize())
                .versionNumber(1)
                .s3Url(s3Service.generatePresignedUrl(s3Key))
                .build();
    }

    @Transactional(readOnly = true)
    public FileInfoResponse getFile(UUID fileId, UUID requestingUserId) {
        FileMetadata file = findActiveFile(fileId);
        assertOwner(file, requestingUserId);

        return FileInfoResponse.builder()
                .fileId(file.getId().toString())
                .fileName(file.getFileName())
                .fileSize(file.getFileSizeBytes())
                .versionNumber(file.getVersionNumber())
                .downloadUrl(s3Service.generatePresignedUrl(file.getS3Key()))
                .build();
    }

    @Transactional(readOnly = true)
    public List<FileListItemResponse> getUserFiles(UUID userId, UUID requestingUserId) {
        log.debug("getUserFiles — pathUserId: [{}], jwtUserId: [{}], equal: {}",
                userId, requestingUserId, userId.equals(requestingUserId));
        if (!userId.equals(requestingUserId)) {
            throw new FileForbiddenException("Access denied to files of another user");
        }
        return fileRepo.findByUserIdAndDeletedFalse(userId).stream()
                .map(f -> FileListItemResponse.builder()
                        .fileId(f.getId().toString())
                        .fileName(f.getFileName())
                        .fileSize(f.getFileSizeBytes())
                        .updatedAt(f.getUpdatedAt())
                        .build())
                .toList();
    }

    public Object updateFile(UUID fileId, MultipartFile file, Integer baseVersion,
                             UUID userId) throws IOException {
        FileMetadata existing = findActiveFile(fileId);
        assertOwner(existing, userId);

        long currentUsage = fileRepo.sumFileSizeByUserId(userId);
        if (currentUsage + file.getSize() > STORAGE_LIMIT_BYTES) {
            throw new StorageLimitExceededException("Storage limit of 5 GB exceeded");
        }

        if (baseVersion != null && baseVersion.equals(existing.getVersionNumber())) {
            return performSafeUpdate(existing, file, userId);
        } else {
            return createConflictCopy(existing, file, userId);
        }
    }

    public MessageResponse deleteFile(UUID fileId, UUID userId) {
        FileMetadata file = findActiveFile(fileId);
        assertOwner(file, userId);

        file.setDeleted(true);
        file.setDeletedAt(LocalDateTime.now());
        fileRepo.save(file);

        eventProducer.publishDeleted(buildEvent("file.deleted", userId, fileId,
                file.getFileName(), file.getFileSizeBytes()));

        return new MessageResponse("File moved to trash");
    }

    @Transactional(readOnly = true)
    public List<VersionListItemResponse> getVersions(UUID fileId, UUID userId) {
        FileMetadata file = findActiveFile(fileId);
        assertOwner(file, userId);

        return versionRepo.findByFileIdOrderByVersionNumberAsc(fileId).stream()
                .map(v -> VersionListItemResponse.builder()
                        .versionNumber(v.getVersionNumber())
                        .createdAt(v.getCreatedAt())
                        .createdBy(v.getCreatedBy().toString())
                        .s3Url(s3Service.generatePresignedUrl(v.getS3Key()))
                        .build())
                .toList();
    }

    public AiSummaryResponse retriggerSummarization(UUID fileId, UUID userId) {
        FileMetadata file = findActiveFile(fileId);
        assertOwner(file, userId);

        byte[] content = s3Service.downloadFile(file.getS3Key());
        summarizationService.processAndPersist(fileId, content, file.getMimeType(), file.getFileName());

        return toAiSummaryResponse(findActiveFile(fileId));
    }

    @Transactional(readOnly = true)
    public AiSummaryResponse getSummary(UUID fileId, UUID userId) {
        FileMetadata file = findActiveFile(fileId);
        assertOwner(file, userId);
        return toAiSummaryResponse(file);
    }

    public RestoreResponse restoreVersion(UUID fileId, int versionNumber, UUID userId) {
        FileMetadata file = findActiveFile(fileId);
        assertOwner(file, userId);

        FileVersion target = versionRepo.findByFileIdAndVersionNumber(fileId, versionNumber)
                .orElseThrow(() -> new VersionNotFoundException(
                        "Version " + versionNumber + " not found for file " + fileId));

        int restoredVersionNumber = file.getVersionNumber() + 1;

        versionRepo.save(FileVersion.builder()
                .fileId(fileId)
                .versionNumber(restoredVersionNumber)
                .s3Key(target.getS3Key())
                .fileSizeBytes(target.getFileSizeBytes())
                .createdBy(userId)
                .build());

        file.setVersionNumber(restoredVersionNumber);
        file.setS3Key(target.getS3Key());
        file.setFileSizeBytes(target.getFileSizeBytes());
        fileRepo.save(file);

        return RestoreResponse.builder()
                .fileId(fileId.toString())
                .restoredVersion(restoredVersionNumber)
                .build();
    }

    // -- private helpers --

    private UpdateResponse performSafeUpdate(FileMetadata existing, MultipartFile file,
                                             UUID userId) throws IOException {
        int newVersion = existing.getVersionNumber() + 1;
        String s3Key = buildS3Key(userId, existing.getId(), newVersion, file.getOriginalFilename());

        s3Service.uploadFile(s3Key, file.getBytes(), file.getContentType());

        versionRepo.save(FileVersion.builder()
                .fileId(existing.getId())
                .versionNumber(newVersion)
                .s3Key(s3Key)
                .fileSizeBytes(file.getSize())
                .createdBy(userId)
                .build());

        existing.setVersionNumber(newVersion);
        existing.setS3Key(s3Key);
        existing.setFileSizeBytes(file.getSize());
        existing.setFileName(file.getOriginalFilename());
        fileRepo.save(existing);

        storageClient.addStorage(userId, file.getSize());

        eventProducer.publishUpdated(buildEvent("file.updated", userId, existing.getId(),
                file.getOriginalFilename(), file.getSize()));

        return UpdateResponse.builder()
                .fileId(existing.getId().toString())
                .newVersionNumber(newVersion)
                .build();
    }

    private ConflictResponse createConflictCopy(FileMetadata existing, MultipartFile file,
                                                UUID userId) throws IOException {
        String conflictName = buildConflictName(existing.getFileName(),
                userId.toString().substring(0, 8));

        UUID conflictId = UUID.randomUUID();
        String s3Key = buildS3Key(userId, conflictId, 1, conflictName);

        s3Service.uploadFile(s3Key, file.getBytes(), file.getContentType());

        FileMetadata conflictCopy = FileMetadata.builder()
                .id(conflictId)
                .userId(userId)
                .fileName(conflictName)
                .fileSizeBytes(file.getSize())
                .s3Key(s3Key)
                .mimeType(file.getContentType())
                .versionNumber(1)
                .conflictCopy(true)
                .originalFileId(existing.getId())
                .build();
        fileRepo.save(conflictCopy);

        versionRepo.save(FileVersion.builder()
                .fileId(conflictId)
                .versionNumber(1)
                .s3Key(s3Key)
                .fileSizeBytes(file.getSize())
                .createdBy(userId)
                .build());

        eventProducer.publishConflict(buildEvent("file.conflict", userId, conflictId,
                conflictName, file.getSize()));

        return ConflictResponse.builder()
                .conflictCopyId(conflictId.toString())
                .message("Conflict detected")
                .build();
    }

    private FileMetadata findActiveFile(UUID fileId) {
        return fileRepo.findByIdAndDeletedFalse(fileId)
                .orElseThrow(() -> new FileNotFoundException("File not found: " + fileId));
    }

    private void assertOwner(FileMetadata file, UUID requestingUserId) {
        if (!file.getUserId().equals(requestingUserId)) {
            throw new FileForbiddenException("Access denied to file: " + file.getId());
        }
    }

    private AiSummaryResponse toAiSummaryResponse(FileMetadata file) {
        return AiSummaryResponse.builder()
                .fileId(file.getId().toString())
                .fileName(file.getFileName())
                .aiSummary(file.getAiSummary())
                .aiDescription(file.getAiDescription())
                .aiTags(file.getAiTags())
                .aiProcessedAt(file.getAiProcessedAt())
                .build();
    }

    private String resolveEmbedText(UUID fileId, String fallback) {
        try {
            return fileRepo.findByIdAndDeletedFalse(fileId)
                    .map(meta -> {
                        StringBuilder sb = new StringBuilder();
                        if (meta.getAiDescription() != null) sb.append(meta.getAiDescription());
                        if (meta.getAiTags() != null) {
                            if (!sb.isEmpty()) sb.append(" ");
                            sb.append(meta.getAiTags());
                        }
                        return sb.isEmpty() ? fallback : sb.toString();
                    })
                    .orElse(fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private String buildS3Key(UUID userId, UUID fileId, int version, String fileName) {
        return userId + "/" + fileId + "/" + version + "/" + fileName;
    }

    private String buildConflictName(String originalName, String userPrefix) {
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        String ext  = dot > 0 ? originalName.substring(dot) : "";
        return base + " (conflicted copy - " + userPrefix + " - " + LocalDate.now() + ")" + ext;
    }

    private FileEvent buildEvent(String eventType, UUID userId, UUID fileId,
                                 String fileName, long fileSize) {
        return FileEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType(eventType)
                .userId(userId)
                .fileId(fileId)
                .fileName(fileName)
                .fileSize(fileSize)
                .timestamp(Instant.now())
                .build();
    }
}
