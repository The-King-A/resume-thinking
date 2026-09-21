package com.resumethinking.platform.interviews;

import com.resumethinking.platform.auth.JwtService;
import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InterviewSessionController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "app.python-internal-service-token=test-internal-token-123456789012")
class InterviewSessionAuthenticationBoundaryTest {
    @Autowired MockMvc mvc;
    @MockitoBean InterviewSessionService service;
    @MockitoBean JwtService jwt;

    @Test
    void authenticatedUserJwtCanCreateAnInterviewSession() throws Exception {
        when(jwt.parse("user-jwt")).thenReturn(Optional.of(new JwtService.Claims("user001", UserRole.USER)));
        when(service.create(any())).thenReturn(InterviewSession.create("session001", "user001", "resume001",
                "revision001", "task001", "profile001", com.resumethinking.platform.matching.JobFamily.JAVA_BACKEND,
                "interview-create-key-0001", Instant.parse("2026-09-20T10:00:00Z")));

        mvc.perform(post("/api/v4/interview-sessions")
                        .header("Authorization", "Bearer user-jwt")
                        .contentType("application/json")
                        .content("{\"matchTaskId\":\"task001\",\"idempotencyKey\":\"interview-create-key-0001\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("session001"));
    }
}
