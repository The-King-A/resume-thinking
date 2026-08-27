package com.resumethinking.platform.matching;

import com.resumethinking.platform.auth.AuthService;
import com.resumethinking.platform.auth.JwtService;
import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.config.SecurityConfig;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MatchTaskController.class)
@Import(SecurityConfig.class)
class MatchTaskHttpBoundaryTest {
    @Autowired MockMvc mvc;
    @MockitoBean MatchTaskService service;
    @MockitoBean JwtService jwt;
    @MockitoBean AuthService authService;

    @Test
    void invalidMatchRequestIsRejectedBeforeTheServiceIsCalled() throws Exception {
        when(jwt.parse(anyString())).thenReturn(Optional.of(new JwtService.Claims(UUID.randomUUID(), UserRole.USER)));

        mvc.perform(post("/api/v1/match-tasks")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":\"not-a-uuid\",\"llmProfileId\":\"not-a-uuid\",\"jobDescriptionText\":\"\",\"idempotencyKey\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void unknownMatchRequestFieldsAreRejectedByThePublicContract() throws Exception {
        when(jwt.parse(anyString())).thenReturn(Optional.of(new JwtService.Claims(UUID.randomUUID(), UserRole.USER)));

        mvc.perform(post("/api/v1/match-tasks")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":\"00000000-0000-0000-0000-000000000001\",\"llmProfileId\":\"00000000-0000-0000-0000-000000000002\",\"jobDescriptionText\":\"Build reliable software with clear communication.\",\"idempotencyKey\":\"valid-key-0000001\",\"ownerId\":\"leak\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void acceptsTheReleasedJavaBackendJobFamily() throws Exception {
        when(jwt.parse(anyString())).thenReturn(Optional.of(new JwtService.Claims(UUID.randomUUID(), UserRole.USER)));
        when(service.createTask(org.mockito.ArgumentMatchers.any())).thenReturn(org.mockito.Mockito.mock(MatchTask.class));

        mvc.perform(post("/api/v1/match-tasks")
                        .header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resumeId\":\"00000000-0000-0000-0000-000000000001\",\"llmProfileId\":\"00000000-0000-0000-0000-000000000002\",\"jobFamily\":\"JAVA_BACKEND\",\"jobDescriptionText\":\"Build reliable software with clear communication.\",\"idempotencyKey\":\"valid-key-0000001\"}"))
                .andExpect(status().isAccepted());
    }
}
