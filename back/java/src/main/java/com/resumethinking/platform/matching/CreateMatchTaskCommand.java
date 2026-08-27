package com.resumethinking.platform.matching;

import java.util.UUID;
import com.resumethinking.platform.auth.UserRole;

public record CreateMatchTaskCommand(UUID actorId, UUID resumeId, UUID llmProfileId, String jobDescriptionText, String idempotencyKey, UserRole role) {
    public CreateMatchTaskCommand(UUID actorId, UUID resumeId, UUID llmProfileId, String jobDescriptionText, String idempotencyKey) {
        this(actorId, resumeId, llmProfileId, jobDescriptionText, idempotencyKey, UserRole.USER);
    }
}
