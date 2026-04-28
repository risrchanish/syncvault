package com.syncvault.fileservice.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UploadResponse {
    private String fileId;
    private String fileName;
    private long fileSize;
    private int versionNumber;
    private String s3Url;
    private String aiSummary;
    private String aiDescription;
    private String aiTags;
    private LocalDateTime aiProcessedAt;
}
