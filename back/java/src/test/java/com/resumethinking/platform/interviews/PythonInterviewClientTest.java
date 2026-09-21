package com.resumethinking.platform.interviews;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class PythonInterviewClientTest {
    @Test
    void v4ClientTargetsInterviewWorkerPathAndRedactsItsDescription() {
        var job = new PythonInterviewClient.InternalInterviewJob(
                "4.0", "QUESTION_GENERATION", "session001", "revision001", "task001", 1,
                1, "callback001", URI.create("http://127.0.0.1:8080/internal/v4/interview-results"),
                "placeholder-callback-token-000000000000", true,
                new PythonInterviewClient.Provider(URI.create("http://127.0.0.1:9000"), "fixture", "fixture-key"),
                java.util.Map.of("requirements", java.util.List.of("private job description")), null, java.util.UUID.randomUUID());

        assertThat(job.toString()).doesNotContain("private job description", "fixture-key", "placeholder-callback-token");
        assertThat(PythonInterviewClient.interviewJobPath()).isEqualTo("/internal/v4/interview-jobs");
    }
}
