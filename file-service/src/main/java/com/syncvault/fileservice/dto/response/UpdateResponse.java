package com.syncvault.fileservice.dto.response;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UpdateResponse {
    private String fileId;
    private int newVersionNumber;
}
