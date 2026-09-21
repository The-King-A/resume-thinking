package com.resumethinking.platform.interviews;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.config.SecurityConfig;
import com.resumethinking.platform.auth.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalInterviewCallbackController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.python-internal-service-token=test-internal-token-123456789012")
class InternalInterviewCallbackSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean InterviewSessionService service;
    @MockitoBean JwtService jwt;

    @BeforeEach
    void setUp() {
        when(jwt.parse(anyString())).thenReturn(java.util.Optional.empty());
        when(service.acceptCallback(any())).thenReturn(new InterviewSessionService.CallbackResponse("ACCEPTED", true));
    }

    @Test
    void validInternalTokenAllowsV4CallbackWithoutJwt() throws Exception {
        mvc.perform(post(SecurityConfig.INTERNAL_INTERVIEW_CALLBACK_V4_PATH)
                        .header(SecurityConfig.INTERNAL_TOKEN_HEADER, "test-internal-token-123456789012")
                        .contentType("application/json").content(mapper.writeValueAsString(failedCallback())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("ACCEPTED"));
    }

    @Test
    void missingOrUserJwtCannotBypassV4InternalToken() throws Exception {
        String body = mapper.writeValueAsString(failedCallback());
        mvc.perform(post(SecurityConfig.INTERNAL_INTERVIEW_CALLBACK_V4_PATH)
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        when(jwt.parse("user-jwt")).thenReturn(java.util.Optional.of(new JwtService.Claims("user001", UserRole.USER)));
        mvc.perform(post(SecurityConfig.INTERNAL_INTERVIEW_CALLBACK_V4_PATH)
                        .header("Authorization", "Bearer user-jwt")
                        .contentType("application/json").content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    private static InterviewAnalysisCallbackRequest failedCallback() {
        return new InterviewAnalysisCallbackRequest("4.0", "QUESTION_GENERATION", "session001", "revision001",
                "task001", 1, 1, "callback001", "c".repeat(32), "", "FAILED", UUID.randomUUID(),
                null, null, "INTERVIEW_MODEL_OUTPUT_INVALID").withComputedPayloadHash();
    }
}
