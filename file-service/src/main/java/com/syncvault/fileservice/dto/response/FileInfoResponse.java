package com.syncvault.fileservice.dto.response;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FileInfoResponse {
    private String fileId;
    private String fileName;
    private long fileSize;
    private int versionNumber;
    private String downloadUrl;
}
