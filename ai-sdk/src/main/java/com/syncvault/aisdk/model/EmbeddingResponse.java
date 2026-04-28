package com.syncvault.aisdk.model;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class EmbeddingResponse {

    /** 1536-dimension vector produced by text-embedding-3-small. */
    private final List<Double> embedding;

    private final int inputTokens;

    private final String modelUsed;
}
