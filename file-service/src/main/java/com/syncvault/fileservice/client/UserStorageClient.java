package com.syncvault.fileservice.client;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class UserStorageClient {

    private static final Logger log = LoggerFactory.getLogger(UserStorageClient.class);

    private final RestTemplate restTemplate;

    @Value("${services.user-service.url:http://localhost:8081}")
    private String userServiceUrl;

    public void addStorage(UUID userId, long bytes) {
        try {
            String url = userServiceUrl + "/users/" + userId + "/storage";
            restTemplate.put(url, Map.of("storageDeltaBytes", bytes));
        } catch (Exception e) {
            // Non-critical: file upload succeeds even if storage tracking call fails
            log.warn("Failed to update storage for user {}: {}", userId, e.getMessage());
        }
    }
}
