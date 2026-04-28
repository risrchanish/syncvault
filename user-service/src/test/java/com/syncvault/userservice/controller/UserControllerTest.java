package com.syncvault.userservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncvault.userservice.dto.request.UpdateProfileRequest;
import com.syncvault.userservice.dto.response.UserProfileResponse;
import com.syncvault.userservice.security.JwtTokenProvider;
import com.syncvault.userservice.security.UserDetailsServiceImpl;
import com.syncvault.userservice.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
class UserControllerTest {

    private static final UUID USER_ID = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @MockitoBean UserService userService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean UserDetailsServiceImpl userDetailsServiceImpl;

    // ---------------------------------------------------------------
    // GET /users/{userId}/profile
    // ---------------------------------------------------------------

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000")
    void getProfile_authorizedUser_returns200() throws Exception {
        when(userService.getProfile(USER_ID)).thenReturn(profileResponse());

        mockMvc.perform(get("/users/{id}/profile", USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.fullName").value("Alice"))
                .andExpect(jsonPath("$.subscriptionPlan").value("FREE"))
                .andExpect(jsonPath("$.storageUsed").value(0));
    }

    @Test
    @WithMockUser(username = "99999999-9999-9999-9999-999999999999")
    void getProfile_differentUser_returns403() throws Exception {
        mockMvc.perform(get("/users/{id}/profile", USER_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void getProfile_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/users/{id}/profile", USER_ID))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------
    // PUT /users/{userId}/profile
    // ---------------------------------------------------------------

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000")
    void updateProfile_authorizedUser_returns200() throws Exception {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setFullName("Alice Updated");

        UserProfileResponse updated = UserProfileResponse.builder()
                .userId(USER_ID.toString()).email("alice@example.com")
                .fullName("Alice Updated").storageUsed(0L).subscriptionPlan("FREE").build();
        when(userService.updateProfile(eq(USER_ID), any())).thenReturn(updated);

        mockMvc.perform(put("/users/{id}/profile", USER_ID).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Alice Updated"));
    }

    @Test
    @WithMockUser(username = "99999999-9999-9999-9999-999999999999")
    void updateProfile_unauthorizedDifferentUser_returns403() throws Exception {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setFullName("Alice Updated");

        mockMvc.perform(put("/users/{id}/profile", USER_ID).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000")
    void updateProfile_nameTooLong_returns400WithFieldError() throws Exception {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setFullName("A".repeat(101)); // exceeds 100-char limit

        mockMvc.perform(put("/users/{id}/profile", USER_ID).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fullName").exists());
    }

    @Test
    @WithMockUser(username = "550e8400-e29b-41d4-a716-446655440000")
    void updateProfile_blankName_returns400() throws Exception {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setFullName("");

        mockMvc.perform(put("/users/{id}/profile", USER_ID).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fullName").exists());
    }

    @Test
    void updateProfile_unauthenticated_returns401() throws Exception {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setFullName("Alice");

        // csrf() passes CSRF check; missing auth then triggers 401
        mockMvc.perform(put("/users/{id}/profile", USER_ID).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private UserProfileResponse profileResponse() {
        return UserProfileResponse.builder()
                .userId(USER_ID.toString()).email("alice@example.com")
                .fullName("Alice").storageUsed(0L).subscriptionPlan("FREE").build();
    }
}
