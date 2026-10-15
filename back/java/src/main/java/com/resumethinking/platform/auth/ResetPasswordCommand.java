package com.resumethinking.platform.auth;

public record ResetPasswordCommand(String identifier, String newPassword) {}
