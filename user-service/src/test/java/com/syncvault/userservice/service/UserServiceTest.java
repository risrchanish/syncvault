package com.syncvault.userservice.service;

import com.syncvault.userservice.dto.request.UpdateProfileRequest;
import com.syncvault.userservice.dto.response.UserProfileResponse;
import com.syncvault.userservice.entity.User;
import com.syncvault.userservice.exception.UserNotFoundException;
import com.syncvault.userservice.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private UserService userService;

    // ---------------------------------------------------------------
    // getProfile
    // ---------------------------------------------------------------

    @Test
    void getProfile_success_returnsProfileWithAllFields() {
        UUID id = UUID.randomUUID();
        User user = user(id, "alice@example.com", "Alice", 1024L, "PRO");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        UserProfileResponse resp = userService.getProfile(id);

        assertThat(resp.getUserId()).isEqualTo(id.toString());
        assertThat(resp.getEmail()).isEqualTo("alice@example.com");
        assertThat(resp.getFullName()).isEqualTo("Alice");
        assertThat(resp.getStorageUsed()).isEqualTo(1024L);
        assertThat(resp.getSubscriptionPlan()).isEqualTo("PRO");
    }

    @Test
    void getProfile_defaultValues_storageZeroAndPlanFree() {
        UUID id = UUID.randomUUID();
        User user = User.builder()
                .id(id)
                .email("bob@example.com")
                .passwordHash("$hash$")
                .fullName("Bob")
                .build(); // defaults: storageUsedBytes=0, subscriptionPlan="FREE"
        when(userRepository.findById(id)).thenReturn(Optional.of(user));

        UserProfileResponse resp = userService.getProfile(id);

        assertThat(resp.getStorageUsed()).isZero();
        assertThat(resp.getSubscriptionPlan()).isEqualTo("FREE");
    }

    @Test
    void getProfile_userNotFound_throwsUserNotFoundException() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getProfile(id))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(id.toString());
    }

    // ---------------------------------------------------------------
    // updateProfile
    // ---------------------------------------------------------------

    @Test
    void updateProfile_success_updatesFullName() {
        UUID id = UUID.randomUUID();
        User user = user(id, "carol@example.com", "Carol", 0L, "FREE");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setFullName("Carol Updated");

        UserProfileResponse resp = userService.updateProfile(id, req);

        assertThat(resp.getFullName()).isEqualTo("Carol Updated");
        assertThat(resp.getEmail()).isEqualTo("carol@example.com"); // email unchanged
        verify(userRepository).save(user);
    }

    @Test
    void updateProfile_persistsChangesToRepository() {
        UUID id = UUID.randomUUID();
        User user = user(id, "dave@example.com", "Dave", 0L, "FREE");
        when(userRepository.findById(id)).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setFullName("New Dave");

        userService.updateProfile(id, req);

        verify(userRepository).save(argThat(u -> "New Dave".equals(u.getFullName())));
    }

    @Test
    void updateProfile_userNotFound_throwsUserNotFoundException() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setFullName("New Name");

        assertThatThrownBy(() -> userService.updateProfile(id, req))
                .isInstanceOf(UserNotFoundException.class)
                .hasMessageContaining(id.toString());

        verify(userRepository, never()).save(any());
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private User user(UUID id, String email, String fullName, long storage, String plan) {
        return User.builder()
                .id(id)
                .email(email)
                .passwordHash("$hash$")
                .fullName(fullName)
                .storageUsedBytes(storage)
                .subscriptionPlan(plan)
                .active(true)
                .build();
    }
}
