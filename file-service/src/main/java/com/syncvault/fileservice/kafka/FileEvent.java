package com.syncvault.fileservice.kafka;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileEvent {
    private UUID eventId;
    private String eventType;
    private UUID userId;
    private UUID fileId;
    private String fileName;
    private long fileSize;
    private Instant timestamp;
}
