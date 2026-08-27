package com.resumethinking.platform.auth;
public record RegisterCommand(String username, String email, String password, UserRole role) {}
