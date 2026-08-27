package com.resumethinking.platform.matching;

import java.util.UUID;
import com.resumethinking.platform.auth.UserRole;

public record CreateMatchTaskCommand(UUID actorId, UUID resumeId, UUID llmProfileId, JobFamily jobFamily,
                                     String jobDescriptionText, String idempotencyKey, UserRole role) {
    public CreateMatchTaskCommand(UUID actorId, UUID resumeId, UUID llmProfileId, JobFamily jobFamily,
                                  String jobDescriptionText, String idempotencyKey) {
        this(actorId, resumeId, llmProfileId, jobFamily, jobDescriptionText, idempotencyKey, UserRole.USER);
    }

    public CreateMatchTaskCommand(UUID actorId, UUID resumeId, UUID llmProfileId, String jobDescriptionText, String idempotencyKey) {
        this(actorId, resumeId, llmProfileId, JobFamily.JAVA_BACKEND, jobDescriptionText, idempotencyKey, UserRole.USER);
    }

    public CreateMatchTaskCommand(UUID actorId, UUID resumeId, UUID llmProfileId, String jobDescriptionText,
                                  String idempotencyKey, UserRole role) {
        this(actorId, resumeId, llmProfileId, JobFamily.JAVA_BACKEND, jobDescriptionText, idempotencyKey, role);
    }
}
