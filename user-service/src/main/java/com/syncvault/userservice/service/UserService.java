package com.syncvault.userservice.service;

import com.syncvault.userservice.dto.request.UpdateProfileRequest;
import com.syncvault.userservice.dto.response.UserProfileResponse;
import com.syncvault.userservice.entity.User;
import com.syncvault.userservice.exception.UserNotFoundException;
import com.syncvault.userservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = findUser(userId);
        return toResponse(user);
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = findUser(userId);
        user.setFullName(request.getFullName());
        user = userRepository.save(user);
        return toResponse(user);
    }

    private User findUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
    }

    private UserProfileResponse toResponse(User user) {
        return UserProfileResponse.builder()
                .userId(user.getId().toString())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .storageUsed(user.getStorageUsedBytes())
                .subscriptionPlan(user.getSubscriptionPlan())
                .build();
    }
}
