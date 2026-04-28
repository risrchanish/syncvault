package com.syncvault.fileservice.dto.response;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConflictResponse {
    private String conflictCopyId;
    private String message;
}
