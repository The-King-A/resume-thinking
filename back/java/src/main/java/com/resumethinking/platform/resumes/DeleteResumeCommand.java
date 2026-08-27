package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import java.util.UUID;

public record DeleteResumeCommand(UUID resumeId, UUID actorId, UserRole role, String confirmationText, long expectedVersion) {}
