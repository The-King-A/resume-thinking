package com.resumethinking.platform.matching;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumethinking.platform.ids.InMemoryReadableIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaAnalysisResultRepositoryTest {
    @Test
    void durableColumnsOverrideStaleJsonIdentity() throws Exception {
        Instant completedAt = Instant.parse("2026-09-02T00:00:00Z");
        AnalysisResult durable = result("task001", "resume001", "revision002", 2, completedAt);
        AnalysisResult staleJson = result("task999", "resume999", "revision999", 999, completedAt);
        String payload = new ObjectMapper().findAndRegisterModules().writeValueAsString(staleJson);
        AnalysisResultEntity entity = new AnalysisResultEntity("result001", durable, payload);
        AnalysisResultJpaRepository delegate = mock(AnalysisResultJpaRepository.class);
        when(delegate.findByTaskId("task001")).thenReturn(Optional.of(entity));
        JpaAnalysisResultRepository repository =
                new JpaAnalysisResultRepository(delegate, new InMemoryReadableIdGenerator());

        AnalysisResult restored = repository.findByTaskId("task001").orElseThrow();

        assertThat(restored.taskId()).isEqualTo("task001");
        assertThat(restored.resumeId()).isEqualTo("resume001");
        assertThat(restored.revisionId()).isEqualTo("revision002");
        assertThat(restored.resumeVersion()).isEqualTo(2);
    }

    private static AnalysisResult result(String taskId, String resumeId, String revisionId,
                                         long resumeVersion, Instant completedAt) {
        return new AnalysisResult(taskId, resumeId, revisionId, resumeVersion,
                "Java backend role", null, List.of(), List.of(), completedAt, "SUCCEEDED", null);
    }
}
