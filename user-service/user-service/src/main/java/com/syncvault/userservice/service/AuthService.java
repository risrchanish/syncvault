package com.syncvault.userservice.service;

import com.syncvault.userservice.dto.request.LoginRequest;
import com.syncvault.userservice.dto.request.LogoutRequest;
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
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Value("${jwt.access.expiration}")
    private long accessTokenExpiryMs;

    @Value("${jwt.refresh.expiration}")
    private long refreshTokenExpiryMs;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new EmailAlreadyExistsException("Email already registered: " + request.getEmail());
        }
        User user = User.builder()
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .build();
        user = userRepository.save(user);
        return new RegisterResponse(user.getId().toString(), "User registered successfully");
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BadCredentialsException("Invalid credentials");
        }

        if (!user.isActive()) {
            throw new BadCredentialsException("Invalid credentials");
        }

        String accessToken = jwtTokenProvider.generateAccessToken(user.getId().toString());
        String rawRefreshToken = issueRefreshToken(user);

        return new AuthResponse(accessToken, rawRefreshToken, accessTokenExpiryMs / 1000);
    }

    @Transactional
    public RefreshResponse refresh(RefreshTokenRequest request) {
        String tokenHash = hashToken(request.getRefreshToken());
        RefreshToken stored = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token"));

        if (stored.isRevoked() || stored.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new InvalidTokenException("Refresh token is expired or revoked");
        }

        // Rotation: revoke old token
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);

        String userId = stored.getUser().getId().toString();
        String newAccessToken = jwtTokenProvider.generateAccessToken(userId);
        String newRawRefreshToken = issueRefreshToken(stored.getUser());

        return new RefreshResponse(newAccessToken, newRawRefreshToken);
    }

    @Transactional
    public void logout(LogoutRequest request) {
        String tokenHash = hashToken(request.getRefreshToken());
        refreshTokenRepository.findByTokenHash(tokenHash).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    private String issueRefreshToken(User user) {
        String rawToken = UUID.randomUUID().toString();
        RefreshToken token = RefreshToken.builder()
                .user(user)
                .tokenHash(hashToken(rawToken))
                .expiresAt(LocalDateTime.now().plusSeconds(refreshTokenExpiryMs / 1000))
                .build();
        refreshTokenRepository.save(token);
        return rawToken;
    }

    // SHA-256 hash — deterministic so we can index and look up by hash
    private String hashToken(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
