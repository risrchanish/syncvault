package com.syncvault.fileservice.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class AiSummaryResponse {
    private final String fileId;
    private final String fileName;
    private final String aiSummary;
    private final String aiDescription;
    private final String aiTags;
    private final LocalDateTime aiProcessedAt;
}
