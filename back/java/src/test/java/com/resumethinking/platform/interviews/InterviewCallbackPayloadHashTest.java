package com.resumethinking.platform.interviews;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InterviewCallbackPayloadHashTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void acceptsTheCanonicalPythonFeedbackCallbackFixtureWithoutAddingNullFields() throws Exception {
        Path fixture = Path.of("..", "..", "contracts", "fixtures", "v4", "interview",
                "callback-feedback-valid.json").normalize();
        InterviewAnalysisCallbackRequest callback = mapper.readValue(Files.readString(fixture),
                InterviewAnalysisCallbackRequest.class);

        assertThat(InterviewCallbackPayloadHash.compute(callback)).isEqualTo(callback.payloadHash());
    }
}
