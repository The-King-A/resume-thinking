package com.resumethinking.platform.matching;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

class MatchResultResponseTest {
    @Test
    void exposesPersistedEvidenceMetadataInsteadOfHardcodedLocation() {
        UUID task = UUID.randomUUID(), resume = UUID.randomUUID(), evidenceId = UUID.randomUUID();
        UUID req = UUID.randomUUID();
        var callback = new AnalysisCallbackRequest.AnalysisResultPayload(
                new AnalysisCallbackRequest.ScoreBreakdown(.8, .7, .6, .5, .4, .7),
                List.of(new AnalysisCallbackRequest.RequirementMatch(req, "Java", "MANDATORY", "SATISFIED", "EXACT", "SKILLS", .9,
                        List.of(new AnalysisCallbackRequest.EvidenceReference(evidenceId, 10, 20, "Java", .95)), "HIGH", null, "SUPPORTED_FACT")),
                List.of());
        var result = new AnalysisResult(task, resume, 2, "Java backend role description", callback.score(), callback.requirements(), callback.suggestions(), Instant.now(), "SUCCEEDED", null);
        var metadata = new AnalysisEvidence(evidenceId, task, "DOCX", "paragraph:4", 0, 100);
        var response = MatchResultResponse.from(result, List.of(metadata));
        assertThat(response.requirements().getFirst().evidence().getFirst().sourceType()).isEqualTo("DOCX");
        assertThat(response.requirements().getFirst().evidence().getFirst().sourceLocation()).isEqualTo("paragraph:4");
    }
}
