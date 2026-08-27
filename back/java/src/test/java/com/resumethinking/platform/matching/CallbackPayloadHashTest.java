package com.resumethinking.platform.matching;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class CallbackPayloadHashTest {
    @Test
    void matchesPythonRfc8785CanonicalJsonForEnvelope() {
        var request = new AnalysisCallbackRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1,
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "token-token-token-token-token-token", "", "FAILED", null,
                "MODEL_UNAVAILABLE", UUID.fromString("00000000-0000-0000-0000-000000000003"));
        assertThat(CallbackPayloadHash.compute(request))
                .isEqualTo("a5a05b22baa0002c7cc014f882587233a25772a4108d9917444638c1f9f4322f");
    }
}
