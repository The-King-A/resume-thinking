package com.resumethinking.platform.interviews;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InternalInterviewCallbackControllerTest {
    @Test
    void callbackResponseKeepsSafeAcceptanceShape() {
        var response = new InterviewSessionService.CallbackResponse("ACCEPTED", true);
        assertThat(response.code()).isEqualTo("ACCEPTED");
        assertThat(response.accepted()).isTrue();
    }
}
