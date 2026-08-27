package com.resumethinking.platform.auth;
import java.time.Instant;
import java.util.UUID;
public record AuthResult(UUID id, String username, String email, UserRole role, String accessToken, String tokenType, long expiresIn, Instant createdAt) {}
