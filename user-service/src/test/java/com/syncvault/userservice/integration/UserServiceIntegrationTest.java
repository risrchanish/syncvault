package com.syncvault.userservice.integration;

import com.syncvault.userservice.dto.request.LoginRequest;
import com.syncvault.userservice.dto.request.RefreshTokenRequest;
import com.syncvault.userservice.dto.request.RegisterRequest;
import com.syncvault.userservice.dto.request.UpdateProfileRequest;
import com.syncvault.userservice.dto.response.AuthResponse;
import com.syncvault.userservice.dto.response.RefreshResponse;
import com.syncvault.userservice.dto.response.RegisterResponse;
import com.syncvault.userservice.dto.response.UserProfileResponse;
import com.syncvault.userservice.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full integration tests using a real PostgreSQL container via Testcontainers.
 * BCryptPasswordEncoder is overridden to strength 4 for test speed.
 * Flyway migrations run automatically against the container DB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class UserServiceIntegrationTest {

    /** Override BCrypt strength 12 → 4 for test speed (saves ~300ms per hash). */
    @TestConfiguration
    static class FastBCryptConfig {
        @Bean
        @Primary
        PasswordEncoder testPasswordEncoder() {
            return new BCryptPasswordEncoder(4);
        }
    }

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired TestRestTemplate restTemplate;

    @Value("${jwt.secret}")
    String jwtSecret;

    // ---------------------------------------------------------------
    // Full flow: register → login → get profile → update profile
    // ---------------------------------------------------------------

    @Test
    void fullFlow_registerLoginGetProfileUpdateProfile() {
        // 1. Register
        ResponseEntity<RegisterResponse> regResp = restTemplate.postForEntity(
                "/auth/register",
                register("flow@example.com", "password123", "Flow User"),
                RegisterResponse.class);

        assertThat(regResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String userId = regResp.getBody().getUserId();
        assertThat(userId).isNotBlank();

        // 2. Login
        ResponseEntity<AuthResponse> loginResp = restTemplate.postForEntity(
                "/auth/login",
                login("flow@example.com", "password123"),
                AuthResponse.class);

        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String accessToken = loginResp.getBody().getAccessToken();
        String refreshToken = loginResp.getBody().getRefreshToken();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotBlank();
        assertThat(loginResp.getBody().getExpiresIn()).isEqualTo(900L);

        // 3. Get profile
        ResponseEntity<UserProfileResponse> profileResp = restTemplate.exchange(
                "/users/{userId}/profile", HttpMethod.GET,
                new HttpEntity<>(bearer(accessToken)), UserProfileResponse.class, userId);

        assertThat(profileResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(profileResp.getBody().getEmail()).isEqualTo("flow@example.com");
        assertThat(profileResp.getBody().getFullName()).isEqualTo("Flow User");
        assertThat(profileResp.getBody().getSubscriptionPlan()).isEqualTo("FREE");

        // 4. Update profile
        UpdateProfileRequest updateReq = new UpdateProfileRequest();
        updateReq.setFullName("Flow User Updated");
        ResponseEntity<UserProfileResponse> updateResp = restTemplate.exchange(
                "/users/{userId}/profile", HttpMethod.PUT,
                new HttpEntity<>(updateReq, bearer(accessToken)), UserProfileResponse.class, userId);

        assertThat(updateResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(updateResp.getBody().getFullName()).isEqualTo("Flow User Updated");
        assertThat(updateResp.getBody().getEmail()).isEqualTo("flow@example.com");
    }

    // ---------------------------------------------------------------
    // Security flow: no token → 401, valid token → 200, expired → 401
    // ---------------------------------------------------------------

    @Test
    void security_noToken_returns401() {
        RegisterResponse reg = restTemplate.postForObject(
                "/auth/register",
                register("notoken@example.com", "password123", "No Token"),
                RegisterResponse.class);

        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/users/{id}/profile", String.class, reg.getUserId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void security_withValidToken_returns200() {
        RegisterResponse reg = restTemplate.postForObject(
                "/auth/register",
                register("validtok@example.com", "password123", "Valid Tok"),
                RegisterResponse.class);

        AuthResponse auth = restTemplate.postForObject(
                "/auth/login",
                login("validtok@example.com", "password123"),
                AuthResponse.class);

        ResponseEntity<UserProfileResponse> resp = restTemplate.exchange(
                "/users/{id}/profile", HttpMethod.GET,
                new HttpEntity<>(bearer(auth.getAccessToken())),
                UserProfileResponse.class, reg.getUserId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void security_withExpiredToken_returns401() {
        RegisterResponse reg = restTemplate.postForObject(
                "/auth/register",
                register("expiredtok@example.com", "password123", "Expired Tok"),
                RegisterResponse.class);

        // Generate a token with expiry set 1 hour in the past
        JwtTokenProvider expiredProvider = new JwtTokenProvider(jwtSecret, -3_600_000L);
        String expiredToken = expiredProvider.generateAccessToken(reg.getUserId());

        ResponseEntity<String> resp = restTemplate.exchange(
                "/users/{id}/profile", HttpMethod.GET,
                new HttpEntity<>(bearer(expiredToken)),
                String.class, reg.getUserId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void security_withTokenForDifferentUser_returns403() {
        // Register two users
        RegisterResponse user1 = restTemplate.postForObject(
                "/auth/register",
                register("user1@example.com", "password123", "User One"),
                RegisterResponse.class);

        restTemplate.postForObject(
                "/auth/register",
                register("user2@example.com", "password123", "User Two"),
                RegisterResponse.class);

        // Login as user2
        AuthResponse auth2 = restTemplate.postForObject(
                "/auth/login",
                login("user2@example.com", "password123"),
                AuthResponse.class);

        // Access user1's profile using user2's token → 403
        ResponseEntity<String> resp = restTemplate.exchange(
                "/users/{id}/profile", HttpMethod.GET,
                new HttpEntity<>(bearer(auth2.getAccessToken())),
                String.class, user1.getUserId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ---------------------------------------------------------------
    // Refresh token rotation
    // ---------------------------------------------------------------

    @Test
    void refreshToken_rotation_oldTokenRejectedAfterUse() {
        restTemplate.postForObject(
                "/auth/register",
                register("rotation@example.com", "password123", "Rotation"),
                RegisterResponse.class);

        AuthResponse auth = restTemplate.postForObject(
                "/auth/login",
                login("rotation@example.com", "password123"),
                AuthResponse.class);

        RefreshTokenRequest refreshReq = new RefreshTokenRequest();
        refreshReq.setRefreshToken(auth.getRefreshToken());

        // First refresh — succeeds
        ResponseEntity<RefreshResponse> first = restTemplate.postForEntity(
                "/auth/refresh", refreshReq, RefreshResponse.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().getAccessToken()).isNotBlank();

        // Second refresh with the SAME old token — must fail (rotation enforced)
        ResponseEntity<String> second = restTemplate.postForEntity(
                "/auth/refresh", refreshReq, String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void refreshToken_expiredRefreshToken_returns401() {
        // Generate a refresh-token-like raw string that was never stored
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/auth/refresh",
                refreshReq("00000000-0000-0000-0000-000000000000"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void register_duplicateEmail_returns409() {
        RegisterRequest req = register("dup@example.com", "password123", "Dup");

        ResponseEntity<String> first = restTemplate.postForEntity("/auth/register", req, String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> second = restTemplate.postForEntity("/auth/register", req, String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void login_wrongPassword_returns401() {
        restTemplate.postForObject(
                "/auth/register",
                register("wrongpass@example.com", "password123", "Wrong Pass"),
                RegisterResponse.class);

        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/auth/login",
                login("wrongpass@example.com", "wrongPassword"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private RegisterRequest register(String email, String password, String name) {
        RegisterRequest r = new RegisterRequest();
        r.setEmail(email);
        r.setPassword(password);
        r.setFullName(name);
        return r;
    }

    private LoginRequest login(String email, String password) {
        LoginRequest r = new LoginRequest();
        r.setEmail(email);
        r.setPassword(password);
        return r;
    }

    private RefreshTokenRequest refreshReq(String token) {
        RefreshTokenRequest r = new RefreshTokenRequest();
        r.setRefreshToken(token);
        return r;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
