package com.syncvault.userservice.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserProfileResponse {
    private String userId;
    private String email;
    private String fullName;
    private long storageUsed;
    private String subscriptionPlan;
}
