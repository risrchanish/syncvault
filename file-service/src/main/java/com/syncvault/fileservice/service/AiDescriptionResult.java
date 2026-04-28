package com.syncvault.fileservice.service;

import java.util.List;

/**
 * Immutable result from the AI description / tagging step.
 * Both fields may be null/empty when the LLM call or JSON parsing fails.
 */
public record AiDescriptionResult(String description, List<String> tags) {

    public static AiDescriptionResult empty() {
        return new AiDescriptionResult(null, List.of());
    }
}
