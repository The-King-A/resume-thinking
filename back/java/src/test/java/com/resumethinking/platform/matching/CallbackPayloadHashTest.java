package com.resumethinking.platform.matching;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CallbackPayloadHashTest {
    @Test
    void matchesPythonRfc8785CanonicalJsonForEnvelope() {
        var request = new AnalysisCallbackRequest(
                "task001", 1,
                "callback001",
                "token-token-token-token-token-token", "", "FAILED", null,
                "MODEL_UNAVAILABLE", UUID.fromString("00000000-0000-0000-0000-000000000003"));
        assertThat(CallbackPayloadHash.compute(request))
                .isEqualTo("883b9569f1af8ab6d1c61e31307ae8fcd5a811740128486cb17329c695a1cb8a");
    }
}
