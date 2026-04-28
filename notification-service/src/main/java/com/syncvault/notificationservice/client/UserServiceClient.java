package com.syncvault.notificationservice.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Slf4j
@Component
public class UserServiceClient {

    private final RestTemplate restTemplate;
    private final String userServiceUrl;

    public UserServiceClient(RestTemplate restTemplate,
                             @Value("${services.user-service.url}") String userServiceUrl) {
        this.restTemplate = restTemplate;
        this.userServiceUrl = userServiceUrl;
    }

    /**
     * Fetches the email address for a given userId from the user-service profile endpoint.
     * Requires user-service to be reachable. Falls back to a placeholder if unavailable.
     */
    @SuppressWarnings("unchecked")
    public String getEmailByUserId(String userId) {
        try {
            String url = userServiceUrl + "/users/" + userId + "/profile";
            Map<String, Object> profile = restTemplate.getForObject(url, Map.class);
            if (profile != null && profile.containsKey("email")) {
                return (String) profile.get("email");
            }
        } catch (Exception e) {
            log.warn("Could not fetch email for userId [{}] from user-service: {}", userId, e.getMessage());
        }
        return userId + "@syncvault-unresolved.internal";
    }
}
