package com.syncvault.fileservice.dto.response;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RestoreResponse {
    private String fileId;
    private int restoredVersion;
}
