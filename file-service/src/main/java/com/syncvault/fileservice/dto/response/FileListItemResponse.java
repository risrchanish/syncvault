package com.syncvault.fileservice.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FileListItemResponse {
    private String fileId;
    private String fileName;
    private long fileSize;
    private LocalDateTime updatedAt;
}
