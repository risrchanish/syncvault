package com.syncvault.userservice.controller;

import com.syncvault.userservice.dto.request.UpdateProfileRequest;
import com.syncvault.userservice.dto.response.UserProfileResponse;
import com.syncvault.userservice.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Tag(name = "User Profile", description = "Get and update user profile")
@SecurityRequirement(name = "bearerAuth")
public class UserController {

    private final UserService userService;

    @GetMapping("/{userId}/profile")
    @Operation(summary = "Get user profile")
    public ResponseEntity<UserProfileResponse> getProfile(
            @PathVariable UUID userId,
            @AuthenticationPrincipal UserDetails principal) {

        if (!principal.getUsername().equals(userId.toString())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(userService.getProfile(userId));
    }

    @PutMapping("/{userId}/profile")
    @Operation(summary = "Update user profile")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @PathVariable UUID userId,
            @Valid @RequestBody UpdateProfileRequest request,
            @AuthenticationPrincipal UserDetails principal) {

        if (!principal.getUsername().equals(userId.toString())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        return ResponseEntity.ok(userService.updateProfile(userId, request));
    }
}
