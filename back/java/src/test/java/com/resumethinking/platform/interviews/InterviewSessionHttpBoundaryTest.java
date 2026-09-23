package com.resumethinking.platform.interviews;

import com.resumethinking.platform.auth.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.matching.JobFamily;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InterviewSessionHttpBoundaryTest {
    @Test
    void v4CreateUsesOnlyTheMatchTaskBindingInItsPublicResponse() throws Exception {
        var service = mock(InterviewSessionService.class);
        when(service.create(any())).thenReturn(InterviewSession.create("session001", "user001", "resume001",
                "revision001", "task001", "profile001", JobFamily.JAVA_BACKEND, "key-000000000001",
                Instant.parse("2026-09-20T10:00:00Z")));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InterviewSessionController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(post("/api/v4/interview-sessions")
                        .contentType("application/json")
                        .content("{\"matchTaskId\":\"task001\",\"idempotencyKey\":\"interview-create-key-0001\"}")
                        .requestAttr("actorId", "user001")
                        .requestAttr("role", UserRole.USER))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").value("session001"))
                .andExpect(jsonPath("$.matchResultId").doesNotExist());
    }

    @Test
    void nextQuestionKeepsTheSessionAndExposesTheNewActiveQuestion() throws Exception {
        var service = mock(InterviewSessionService.class);
        var session = InterviewSession.create("session001", "user001", "resume001", "revision001",
                "task001", "profile001", JobFamily.JAVA_BACKEND, "key-000000000001",
                Instant.parse("2026-09-20T10:00:00Z"));
        session.startQuestionGeneration("callback001", "placeholder-callback-token-000000000000", 1,
                Instant.parse("2026-09-20T10:00:00Z"));
        session.acceptQuestions(4, "question001", Instant.parse("2026-09-20T10:00:01Z"));
        session.startAnswerAnalysis("answer001", "question001", "callback002",
                "placeholder-callback-token-000000000000", 1, Instant.parse("2026-09-20T10:00:02Z"));
        session.acceptFeedback("feedback001", Instant.parse("2026-09-20T10:00:03Z"));
        session.moveToNextQuestion("question002", Instant.parse("2026-09-20T10:00:04Z"));
        when(service.moveToNextQuestion("session001", "user001", 7)).thenReturn(session);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InterviewSessionController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(post("/api/v4/interview-sessions/session001/next-question")
                        .contentType("application/json")
                        .content("{\"expectedSessionVersion\":7}")
                        .requestAttr("actorId", "user001")
                        .requestAttr("role", UserRole.USER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("WAITING_FOR_ANSWER"))
                .andExpect(jsonPath("$.activeQuestionId").value("question002"));
    }

    @Test
    void questionListMarksPreviouslyAnsweredQuestionsWithoutExposingAnswerText() throws Exception {
        var service = mock(InterviewSessionService.class);
        var session = InterviewSession.create("session001", "user001", "resume001", "revision001",
                "task001", "profile001", JobFamily.JAVA_BACKEND, "key-000000000001",
                Instant.parse("2026-09-20T10:00:00Z"));
        var question = new InterviewQuestion("question001", "session001", 1, "BASIC_CONFIRMATION", "BASIC",
                "说明职责", "requirement001", "Java 服务开发", "[\"evidence001\"]", "核对职责", .9,
                Instant.parse("2026-09-20T10:00:00Z"));
        when(service.getSession("session001", "user001")).thenReturn(session);
        when(service.getQuestions("session001", "user001")).thenReturn(List.of(question));
        when(service.getAnsweredQuestionIds("session001", "user001")).thenReturn(Set.of("question001"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InterviewSessionController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/api/v4/interview-sessions/session001/questions")
                        .requestAttr("actorId", "user001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.questions[0].answered").value(true))
                .andExpect(jsonPath("$.questions[0].answerText").doesNotExist());
    }

    @Test
    void failedInterviewFeedbackUsesTerminalErrorInsteadOfConflictRetry() throws Exception {
        var service = mock(InterviewSessionService.class);
        when(service.getFeedback("session001", "user001"))
                .thenThrow(new InterviewSessionFailedException("INTERVIEW_MODEL_OUTPUT_INVALID"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new InterviewSessionController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();

        mvc.perform(get("/api/v4/interview-sessions/session001/feedback")
                        .requestAttr("actorId", "user001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INTERVIEW_MODEL_OUTPUT_INVALID"))
                .andExpect(jsonPath("$.retryable").value(false));
    }
}
