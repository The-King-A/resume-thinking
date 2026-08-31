package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;

public record DeleteResumeCommand(String resumeId, String actorId, UserRole role, String confirmationText, long expectedVersion) {}
