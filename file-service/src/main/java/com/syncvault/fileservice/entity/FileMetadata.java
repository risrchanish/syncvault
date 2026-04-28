package com.syncvault.fileservice.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "files")
public class FileMetadata {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_size_bytes", nullable = false)
    private long fileSizeBytes;

    @Column(name = "s3_key", nullable = false, length = 500)
    private String s3Key;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "version_number")
    @Builder.Default
    private int versionNumber = 1;

    @Column(name = "is_deleted")
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "is_conflict_copy")
    @Builder.Default
    private boolean conflictCopy = false;

    @Column(name = "original_file_id", columnDefinition = "uuid")
    private UUID originalFileId;

    @Column(name = "ai_summary", columnDefinition = "TEXT")
    private String aiSummary;

    @Column(name = "ai_description", length = 500)
    private String aiDescription;

    @Column(name = "ai_tags", length = 255)
    private String aiTags;

    @Column(name = "ai_processed_at")
    private LocalDateTime aiProcessedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
