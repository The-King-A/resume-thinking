package com.resumethinking.platform.auth;
import java.time.Instant;
public record AuthResult(String id, String username, String email, UserRole role, String accessToken, String tokenType, long expiresIn, Instant createdAt) {}
