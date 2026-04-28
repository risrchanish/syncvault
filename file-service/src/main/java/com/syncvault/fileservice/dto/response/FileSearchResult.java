package com.syncvault.fileservice.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class FileSearchResult {
    private final String fileId;
    private final String fileName;
    private final long fileSize;
    private final double relevanceScore;
}
