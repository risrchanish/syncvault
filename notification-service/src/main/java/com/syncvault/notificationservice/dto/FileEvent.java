package com.syncvault.notificationservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileEvent {
    private String eventId;
    private String eventType;
    private String userId;
    private String fileId;
    private String fileName;
    private Long fileSize;
    private String timestamp;
    /** Present on file.updated events once file-service publishes it. Nullable. */
    private Integer versionNumber;
}
