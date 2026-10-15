package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.ApiExceptionHandler;
import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.matching.JobFamily;
import com.resumethinking.platform.matching.MatchTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MatchSubmissionHttpBoundaryTest {
    private MatchSubmissionService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MatchSubmissionService.class);
        when(service.submitInitial(any(), any(), any(), any())).thenReturn(task());
        mvc = MockMvcBuilders.standaloneSetup(new MatchSubmissionController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void v3InitialMultipartAcceptsTxtAndDocx() throws Exception {
        for (String filename : new String[]{"resume.txt", "resume.docx"}) {
            mvc.perform(validRequest(filename)).andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.revisionId").value("revision901"))
                    .andExpect(jsonPath("$.publicationState").value("PENDING"));
        }
    }

    @Test
    void v3InitialMultipartRejectsPdfWithSafeError() throws Exception {
        mvc.perform(validRequest("private-resume.pdf"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FILE"))
                .andExpect(jsonPath("$.message").value("UNSUPPORTED_FILE"))
                .andExpect(jsonPath("$.detailCode").isEmpty());
    }

    @Test
    void v3MultipartRejectsMalformedBusinessIdsBeforeDelegation() throws Exception {
        mvc.perform(request("resume.txt", "Java CV", "bad-profile"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mvc.perform(multipart("/api/v3/resumes/bad-resume/match-submissions")
                        .param("expectedEffectiveRevisionId", "revision901")
                        .param("llmProfileId", "profile901").param("jobFamily", "JAVA_BACKEND")
                        .param("jobDescriptionText", "Build reliable Java services with clear tests and ownership.")
                        .param("idempotencyKey", "http-rematch-key1")
                        .requestAttr("actorId", "user901").requestAttr("role", UserRole.USER))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v3/resumes/resume901/match-submissions")
                        .param("expectedEffectiveRevisionId", "bad-revision")
                        .param("llmProfileId", "profile901").param("jobFamily", "JAVA_BACKEND")
                        .param("jobDescriptionText", "Build reliable Java services with clear tests and ownership.")
                        .param("idempotencyKey", "http-rematch-key2")
                        .requestAttr("actorId", "user901").requestAttr("role", UserRole.USER))
                .andExpect(status().isBadRequest());
        verify(service, never()).rematch(any(), any(), any(), any(), any(), any());
    }

    @Test
    void v3MultipartRejectsAnExplicitBlankTitle() throws Exception {
        mvc.perform(request("resume.txt", "   ", "profile901"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void v3MultipartRejectsUnsupportedJobFamilyAndShortJobText() throws Exception {
        mvc.perform(multipart("/api/v3/match-submissions")
                        .file(new MockMultipartFile("file", "resume.txt", MediaType.TEXT_PLAIN_VALUE, "Java".getBytes()))
                        .param("llmProfileId", "profile901").param("jobFamily", "DATA_ANALYST")
                        .param("jobDescriptionText", "short").param("idempotencyKey", "http-key-0000001")
                        .requestAttr("actorId", "user901").requestAttr("role", UserRole.USER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder validRequest(String filename) {
        return request(filename, "Java CV", "profile901");
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(
            String filename, String title, String profileId) {
        return multipart("/api/v3/match-submissions")
                .file(new MockMultipartFile("file", filename, MediaType.APPLICATION_OCTET_STREAM_VALUE,
                        "Java services".getBytes()))
                .param("title", title).param("llmProfileId", profileId)
                .param("jobFamily", "JAVA_BACKEND").param("jobDescriptionText",
                        "Build reliable Java services with clear tests and operational ownership.")
                .param("idempotencyKey", "http-key-0000001")
                .requestAttr("actorId", "user901").requestAttr("role", UserRole.USER);
    }

    private static MatchTask task() {
        return new MatchTask("task901", "callback901", "resume901", "revision901", "profile901", "user901",
                1, JobFamily.JAVA_BACKEND,
                "Build reliable Java services with clear tests and operational ownership.",
                "http-key-0000001", "token-token-token-token-token-token", Set.of("evidence901"),
                MatchTask.PublicationState.PENDING, Instant.parse("2026-09-02T00:00:00Z"));
    }
}
