package com.resumethinking.platform.resumes;

public record ResumeMatchContext(
        String title,
        String effectiveRevisionId,
        String pendingRevisionId,
        String latestSuccessfulTaskId,
        String llmProfileId,
        String jobDescriptionText) {
}
