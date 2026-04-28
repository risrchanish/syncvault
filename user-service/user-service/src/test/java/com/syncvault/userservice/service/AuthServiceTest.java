package com.syncvault.userservice.service;

import com.syncvault.userservice.dto.request.LoginRequest;
import com.syncvault.userservice.dto.request.RefreshTokenRequest;
import com.syncvault.userservice.dto.request.RegisterRequest;
import com.syncvault.userservice.dto.response.AuthResponse;
import com.syncvault.userservice.dto.response.RefreshResponse;
import com.syncvault.userservice.dto.response.RegisterResponse;
import com.syncvault.userservice.entity.RefreshToken;
import com.syncvault.userservice.entity.User;
import com.syncvault.userservice.exception.EmailAlreadyExistsException;
import com.syncvault.userservice.exception.InvalidTokenException;
import com.syncvault.userservice.repository.RefreshTokenRepository;
import com.syncvault.userservice.repository.UserRepository;
import com.syncvault.userservice.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private AuthService authService;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(authService, "accessTokenExpiryMs", 900_000L);
        ReflectionTestUtils.setField(authService, "refreshTokenExpiryMs", 604_800_000L);
    }

    // ---------------------------------------------------------------
    // register
    // ---------------------------------------------------------------

    @Test
    void register_success_returnsUserIdAndMessage() {
        UUID savedId = UUID.randomUUID();
        User saved = user(savedId, "alice@example.com", true);

        when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("$hashed$");
        when(userRepository.save(any(User.class))).thenReturn(saved);

        RegisterResponse resp = authService.register(registerReq("alice@example.com", "password123", "Alice"));

        assertThat(resp.getUserId()).isEqualTo(savedId.toString());
        assertThat(resp.getMessage()).isEqualTo("User registered successfully");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_duplicateEmail_throwsEmailAlreadyExistsException() {
        when(userRepository.existsByEmail("dup@example.com")).thenReturn(true);

        RegisterRequest dupReq = registerReq("dup@example.com", "password123", "Bob");
        assertThatThrownBy(() -> authService.register(dupReq))
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessageContaining("dup@example.com");

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_passwordEncoded_beforePersisting() {
        UUID savedId = UUID.randomUUID();
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode("rawPassword")).thenReturn("$bcrypt$");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            u = User.builder().id(savedId).email(u.getEmail())
                    .passwordHash(u.getPasswordHash()).fullName(u.getFullName()).build();
            return u;
        });

        authService.register(registerReq("x@x.com", "rawPassword", "X"));

        verify(passwordEncoder).encode("rawPassword");
        verify(userRepository).save(argThat(u -> "$bcrypt$".equals(u.getPasswordHash())));
    }

    // ---------------------------------------------------------------
    // login
    // ---------------------------------------------------------------

    @Test
    void login_success_returnsTokensAndExpiry() {
        UUID userId = UUID.randomUUID();
        User active = user(userId, "alice@example.com", true);

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("password123", "$hashed$")).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(userId.toString())).thenReturn("access.jwt");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));

        AuthResponse resp = authService.login(loginReq("alice@example.com", "password123"));

        assertThat(resp.getAccessToken()).isEqualTo("access.jwt");
        assertThat(resp.getRefreshToken()).isNotNull().isNotBlank();
        assertThat(resp.getExpiresIn()).isEqualTo(900L); // 900000ms / 1000
    }

    @Test
    void login_wrongPassword_throwsBadCredentialsException() {
        User active = user(UUID.randomUUID(), "alice@example.com", true);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches("wrong", "$hashed$")).thenReturn(false);

        LoginRequest wrongPassReq = loginReq("alice@example.com", "wrong");
        assertThatThrownBy(() -> authService.login(wrongPassReq))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_userNotFound_throwsBadCredentialsException() {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        LoginRequest ghostReq = loginReq("ghost@example.com", "password123");
        assertThatThrownBy(() -> authService.login(ghostReq))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_inactiveUser_throwsBadCredentialsException() {
        User inactive = user(UUID.randomUUID(), "locked@example.com", false);
        when(userRepository.findByEmail("locked@example.com")).thenReturn(Optional.of(inactive));
        when(passwordEncoder.matches("password123", "$hashed$")).thenReturn(true);

        LoginRequest lockedReq = loginReq("locked@example.com", "password123");
        assertThatThrownBy(() -> authService.login(lockedReq))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_storesRefreshTokenHashedInDb() {
        UUID userId = UUID.randomUUID();
        User active = user(userId, "bob@example.com", true);
        when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(active));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
        when(jwtTokenProvider.generateAccessToken(anyString())).thenReturn("tok");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));

        AuthResponse resp = authService.login(loginReq("bob@example.com", "password123"));

        // Raw refresh token returned to client must be a UUID string, not the hash
        assertThat(resp.getRefreshToken()).hasSize(36); // UUID format
        verify(refreshTokenRepository).save(argThat(rt -> !rt.getTokenHash().equals(resp.getRefreshToken())));
    }

    // ---------------------------------------------------------------
    // refresh token
    // ---------------------------------------------------------------

    @Test
    void refresh_success_returnsNewTokens() {
        UUID userId = UUID.randomUUID();
        User owner = user(userId, "carol@example.com", true);
        RefreshToken valid = refreshToken(owner, LocalDateTime.now().plusDays(7), false);

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(valid));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));
        when(jwtTokenProvider.generateAccessToken(userId.toString())).thenReturn("new.access.jwt");

        RefreshResponse resp = authService.refresh(refreshReq("any-raw-token"));

        assertThat(resp.getAccessToken()).isEqualTo("new.access.jwt");
        assertThat(resp.getRefreshToken()).isNotNull().isNotBlank();
    }

    @Test
    void refresh_expiredToken_throwsInvalidTokenException() {
        RefreshToken expired = refreshToken(
                user(UUID.randomUUID(), "e@e.com", true),
                LocalDateTime.now().minusDays(1), false);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(expired));

        RefreshTokenRequest expiredReq = refreshReq("any");
        assertThatThrownBy(() -> authService.refresh(expiredReq))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired or revoked");
    }

    @Test
    void refresh_revokedToken_throwsInvalidTokenException() {
        RefreshToken revoked = refreshToken(
                user(UUID.randomUUID(), "r@r.com", true),
                LocalDateTime.now().plusDays(7), true);
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(revoked));

        RefreshTokenRequest revokedReq = refreshReq("any");
        assertThatThrownBy(() -> authService.refresh(revokedReq))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired or revoked");
    }

    @Test
    void refresh_tokenNotFound_throwsInvalidTokenException() {
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.empty());

        RefreshTokenRequest notFoundReq = refreshReq("nonexistent");
        assertThatThrownBy(() -> authService.refresh(notFoundReq))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("Invalid refresh token");
    }

    @Test
    void refresh_tokenRotation_oldTokenRevokedAfterUse() {
        UUID userId = UUID.randomUUID();
        User owner = user(userId, "dave@example.com", true);
        RefreshToken valid = refreshToken(owner, LocalDateTime.now().plusDays(7), false);

        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(valid));
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(i -> i.getArgument(0));
        when(jwtTokenProvider.generateAccessToken(anyString())).thenReturn("tok");

        authService.refresh(refreshReq("first-use"));

        // Old token is now revoked
        assertThat(valid.isRevoked()).isTrue();

        // Simulate second call returning the already-revoked token
        when(refreshTokenRepository.findByTokenHash(anyString())).thenReturn(Optional.of(valid));
        RefreshTokenRequest reusedReq = refreshReq("first-use");
        assertThatThrownBy(() -> authService.refresh(reusedReq))
                .isInstanceOf(InvalidTokenException.class)
                .hasMessageContaining("expired or revoked");
    }

    // ---------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------

    private RegisterRequest registerReq(String email, String password, String name) {
        RegisterRequest r = new RegisterRequest();
        r.setEmail(email);
        r.setPassword(password);
        r.setFullName(name);
        return r;
    }

    private LoginRequest loginReq(String email, String password) {
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

    private User user(UUID id, String email, boolean active) {
        return User.builder()
                .id(id)
                .email(email)
                .passwordHash("$hashed$")
                .fullName("Test User")
                .active(active)
                .build();
    }

    private RefreshToken refreshToken(User owner, LocalDateTime expiresAt, boolean revoked) {
        return RefreshToken.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .tokenHash("sha256hash")
                .expiresAt(expiresAt)
                .revoked(revoked)
                .build();
    }
}
