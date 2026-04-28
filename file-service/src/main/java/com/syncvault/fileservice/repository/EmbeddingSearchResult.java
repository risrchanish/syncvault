package com.syncvault.fileservice.repository;

import java.util.UUID;

public record EmbeddingSearchResult(UUID fileId, double score) {}
