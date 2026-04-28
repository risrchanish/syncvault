package com.syncvault.fileservice.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VersionListItemResponse {
    private int versionNumber;
    private LocalDateTime createdAt;
    private String createdBy;
    private String s3Url;
}
