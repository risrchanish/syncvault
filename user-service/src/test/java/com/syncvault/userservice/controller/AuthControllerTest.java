package com.syncvault.userservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncvault.userservice.dto.request.LoginRequest;
import com.syncvault.userservice.dto.request.LogoutRequest;
import com.syncvault.userservice.dto.request.RefreshTokenRequest;
import com.syncvault.userservice.dto.request.RegisterRequest;
import com.syncvault.userservice.dto.response.AuthResponse;
import com.syncvault.userservice.dto.response.RefreshResponse;
import com.syncvault.userservice.dto.response.RegisterResponse;
import com.syncvault.userservice.security.JwtTokenProvider;
import com.syncvault.userservice.security.UserDetailsServiceImpl;
import com.syncvault.userservice.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller-slice tests for AuthController.
 *
 * NOTE: @WebMvcTest loads the default Spring Security filter chain (not our custom SecurityConfig)
 * because SecurityConfig's transitive dependency JwtAuthenticationFilter cannot be created in
 * the web-layer-only context. As a result, all endpoints require authentication by default.
 * We use @WithMockUser on public endpoints so requests reach the controller.
 * Actual unauthenticated-access behaviour (401/403) is fully verified in UserServiceIntegrationTest.
 */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper mapper;

    @MockitoBean AuthService authService;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean UserDetailsServiceImpl userDetailsServiceImpl;

    // ---------------------------------------------------------------
    // POST /auth/register — validation + success
    // ---------------------------------------------------------------

    @Test
    @WithMockUser
    void register_success_returns201WithUserId() throws Exception {
        when(authService.register(any())).thenReturn(new RegisterResponse("uuid-1", "User registered successfully"));

        mockMvc.perform(post("/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(validRegisterRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value("uuid-1"))
                .andExpect(jsonPath("$.message").value("User registered successfully"));
    }

    @Test
    @WithMockUser
    void register_invalidEmailFormat_returns400WithFieldError() throws Exception {
        RegisterRequest req = validRegisterRequest();
        req.setEmail("not-an-email");

        mockMvc.perform(post("/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    @WithMockUser
    void register_blankEmail_returns400() throws Exception {
        RegisterRequest req = validRegisterRequest();
        req.setEmail("");

        mockMvc.perform(post("/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    @WithMockUser
    void register_passwordTooShort_returns400WithFieldError() throws Exception {
        RegisterRequest req = validRegisterRequest();
        req.setPassword("short"); // less than 8 chars

        mockMvc.perform(post("/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @WithMockUser
    void register_blankFullName_returns400() throws Exception {
        RegisterRequest req = validRegisterRequest();
        req.setFullName("");

        mockMvc.perform(post("/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fullName").exists());
    }

    @Test
    @WithMockUser
    void register_fullNameTooLong_returns400() throws Exception {
        RegisterRequest req = validRegisterRequest();
        req.setFullName("A".repeat(101));

        mockMvc.perform(post("/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fullName").exists());
    }

    // ---------------------------------------------------------------
    // POST /auth/login
    // ---------------------------------------------------------------

    @Test
    @WithMockUser
    void login_success_returns200WithTokens() throws Exception {
        when(authService.login(any())).thenReturn(new AuthResponse("access.tok", "refresh.tok", 900L));

        LoginRequest req = new LoginRequest();
        req.setEmail("alice@example.com");
        req.setPassword("password123");

        mockMvc.perform(post("/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.tok"))
                .andExpect(jsonPath("$.refreshToken").value("refresh.tok"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    @WithMockUser
    void login_blankEmail_returns400() throws Exception {
        LoginRequest req = new LoginRequest();
        req.setEmail("");
        req.setPassword("password123");

        mockMvc.perform(post("/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------
    // POST /auth/refresh
    // ---------------------------------------------------------------

    @Test
    @WithMockUser
    void refresh_success_returns200WithNewTokens() throws Exception {
        when(authService.refresh(any())).thenReturn(new RefreshResponse("new.access", "new.refresh"));

        RefreshTokenRequest req = new RefreshTokenRequest();
        req.setRefreshToken("some-refresh-token");

        mockMvc.perform(post("/auth/refresh").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access"))
                .andExpect(jsonPath("$.refreshToken").value("new.refresh"));
    }

    // ---------------------------------------------------------------
    // POST /auth/logout  (requires authentication)
    // ---------------------------------------------------------------

    @Test
    @WithMockUser
    void logout_withValidToken_returns200() throws Exception {
        doNothing().when(authService).logout(any());

        LogoutRequest req = new LogoutRequest();
        req.setRefreshToken("some-refresh-token");

        mockMvc.perform(post("/auth/logout").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));
    }

    @Test
    void logout_withoutAuthentication_returns4xx() throws Exception {
        // Default web-layer security: unauthenticated → 401 or 403 depending on CSRF status.
        // Full 401-specifically is verified in UserServiceIntegrationTest.
        LogoutRequest req = new LogoutRequest();
        req.setRefreshToken("some-refresh-token");

        mockMvc.perform(post("/auth/logout").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().is4xxClientError());
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private RegisterRequest validRegisterRequest() {
        RegisterRequest r = new RegisterRequest();
        r.setEmail("alice@example.com");
        r.setPassword("password123");
        r.setFullName("Alice");
        return r;
    }
}
