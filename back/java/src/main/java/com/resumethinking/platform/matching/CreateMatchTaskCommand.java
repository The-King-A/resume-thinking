package com.resumethinking.platform.matching;

import com.resumethinking.platform.auth.UserRole;

public record CreateMatchTaskCommand(String actorId, String resumeId, String llmProfileId, JobFamily jobFamily,
                                     String jobDescriptionText, String idempotencyKey, UserRole role,
                                     String submissionFingerprint) {
    public CreateMatchTaskCommand(String actorId, String resumeId, String llmProfileId, JobFamily jobFamily,
                                  String jobDescriptionText, String idempotencyKey, UserRole role) {
        this(actorId, resumeId, llmProfileId, jobFamily, jobDescriptionText, idempotencyKey, role, null);
    }

    public CreateMatchTaskCommand(String actorId, String resumeId, String llmProfileId, JobFamily jobFamily,
                                  String jobDescriptionText, String idempotencyKey) {
        this(actorId, resumeId, llmProfileId, jobFamily, jobDescriptionText, idempotencyKey, UserRole.USER, null);
    }

    public CreateMatchTaskCommand(String actorId, String resumeId, String llmProfileId, String jobDescriptionText, String idempotencyKey) {
        this(actorId, resumeId, llmProfileId, JobFamily.JAVA_BACKEND, jobDescriptionText, idempotencyKey, UserRole.USER, null);
    }

    public CreateMatchTaskCommand(String actorId, String resumeId, String llmProfileId, String jobDescriptionText,
                                  String idempotencyKey, UserRole role) {
        this(actorId, resumeId, llmProfileId, JobFamily.JAVA_BACKEND, jobDescriptionText, idempotencyKey, role, null);
    }
}
