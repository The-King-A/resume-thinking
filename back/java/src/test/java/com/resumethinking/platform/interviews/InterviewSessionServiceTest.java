package com.resumethinking.platform.interviews;

import com.resumethinking.platform.TestIds;
import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.crypto.AesGcmCryptoService;
import com.resumethinking.platform.matching.AnalysisEvidence;
import com.resumethinking.platform.matching.AnalysisEvidenceRepository;
import com.resumethinking.platform.matching.AnalysisResult;
import com.resumethinking.platform.matching.AnalysisResultRepository;
import com.resumethinking.platform.matching.AnalysisCallbackRequest;
import com.resumethinking.platform.matching.JobFamily;
import com.resumethinking.platform.matching.MatchTask;
import com.resumethinking.platform.matching.MatchTaskRepository;
import com.resumethinking.platform.profiles.DispatchLlmProfile;
import com.resumethinking.platform.profiles.LlmProfileService;
import com.resumethinking.platform.resumes.Resume;
import com.resumethinking.platform.resumes.ResumeLifecycleService;
import com.resumethinking.platform.resumes.ResumeRepository;
import com.resumethinking.platform.resumes.ResumeRevisionRepository;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InterviewSessionServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    @Test
    void createsAnOwnerScopedQuestionGenerationSessionFromASucceededEffectiveMatch() {
        Fixture fixture = fixture();

        InterviewSession created = fixture.service.create(new InterviewSessionService.CreateInterviewSessionCommand(
                "user001", UserRole.USER, "task001", "interview-create-key-0001"));

        assertThat(created.getState()).isEqualTo(InterviewSession.State.QUESTION_GENERATING);
        assertThat(created.getResumeId()).isEqualTo("resume001");
        assertThat(created.getRevisionId()).isEqualTo("revision001");
        verify(fixture.python).dispatch(any());
    }

    @Test
    void repeatsTheOriginalSessionForTheSameOwnerAndIdempotencyKey() {
        Fixture fixture = fixture();
        var command = new InterviewSessionService.CreateInterviewSessionCommand(
                "user001", UserRole.USER, "task001", "interview-create-key-0001");

        InterviewSession first = fixture.service.create(command);
        InterviewSession second = fixture.service.create(command);

        assertThat(second.getId()).isEqualTo(first.getId());
        verify(fixture.python, times(1)).dispatch(any());
    }

    @Test
    void rejectsAUserWhoDoesNotOwnTheSuccessfulMatchTask() {
        Fixture fixture = fixture();

        assertThatThrownBy(() -> fixture.service.create(new InterviewSessionService.CreateInterviewSessionCommand(
                "user999", UserRole.USER, "task001", "interview-create-key-0001")))
                .isInstanceOf(InterviewMatchNotReadyException.class);
        verify(fixture.python, never()).dispatch(any());
    }

    @Test
    void acceptsAnEvidenceBoundQuestionCallbackAndMovesToWaitingForAnswer() {
        Fixture fixture = fixture();
        InterviewSession session = fixture.service.create(new InterviewSessionService.CreateInterviewSessionCommand(
                "user001", UserRole.USER, "task001", "interview-create-key-0001"));
        var callback = new InterviewAnalysisCallbackRequest(
                "4.0", "QUESTION_GENERATION", session.getId(), "revision001", "task001", session.getVersion(),
                session.getAttempt(), session.getCallbackId(), session.callbackTokenForTests(), "", "SUCCEEDED",
                java.util.UUID.randomUUID(), questions(), null, null).withComputedPayloadHash();

        assertThat(fixture.service.acceptCallback(callback).code()).isEqualTo("ACCEPTED");
        InterviewSession ready = fixture.service.getSession(session.getId(), "user001");
        assertThat(ready.getState()).isEqualTo(InterviewSession.State.WAITING_FOR_ANSWER);
        assertThat(ready.getCurrentQuestionId()).isEqualTo("question001");
    }

    @Test
    void encryptsOneAnswerAndDispatchesAnswerAnalysisOnlyOncePerQuestion() {
        Fixture fixture = fixture();
        InterviewSession session = fixture.service.create(new InterviewSessionService.CreateInterviewSessionCommand(
                "user001", UserRole.USER, "task001", "interview-create-key-0001"));
        var callback = new InterviewAnalysisCallbackRequest("4.0", "QUESTION_GENERATION", session.getId(), "revision001", "task001",
                session.getVersion(), session.getAttempt(), session.getCallbackId(), session.callbackTokenForTests(), "", "SUCCEEDED",
                java.util.UUID.randomUUID(), questions(), null, null).withComputedPayloadHash();
        fixture.service.acceptCallback(callback);
        InterviewSession ready = fixture.service.getSession(session.getId(), "user001");

        InterviewAnswer answer = fixture.service.submitAnswer(ready.getId(), "user001",
                new InterviewSessionService.SubmitInterviewAnswerCommand("question001", "我负责接口设计和异常处理。",
                        ready.getVersion(), "interview-answer-key-0001"));

        assertThat(answer.getCiphertext()).isNotEqualTo("我负责接口设计和异常处理。".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(fixture.service.getSession(ready.getId(), "user001").getState()).isEqualTo(InterviewSession.State.ANSWER_ANALYZING);
        assertThatThrownBy(() -> fixture.service.submitAnswer(ready.getId(), "user001",
                new InterviewSessionService.SubmitInterviewAnswerCommand("question001", "另一份回答", ready.getVersion(), "interview-answer-key-0002")))
                .isInstanceOf(InterviewAnswerConflictException.class);
    }

    @Test
    void movesToTheNextUnansweredQuestionInSequenceAfterFeedback() {
        Fixture fixture = fixture();
        InterviewSession session = fixture.service.create(new InterviewSessionService.CreateInterviewSessionCommand(
                "user001", UserRole.USER, "task001", "interview-create-key-0003"));
        var questionsCallback = new InterviewAnalysisCallbackRequest("4.0", "QUESTION_GENERATION", session.getId(),
                "revision001", "task001", session.getVersion(), session.getAttempt(), session.getCallbackId(),
                session.callbackTokenForTests(), "", "SUCCEEDED", java.util.UUID.randomUUID(), questions(), null, null)
                .withComputedPayloadHash();
        fixture.service.acceptCallback(questionsCallback);
        InterviewSession ready = fixture.service.getSession(session.getId(), "user001");
        InterviewAnswer answer = fixture.service.submitAnswer(session.getId(), "user001",
                new InterviewSessionService.SubmitInterviewAnswerCommand("question002", "我负责接口设计。",
                        ready.getVersion(), "interview-answer-key-0003"));
        InterviewSession analyzing = fixture.service.getSession(session.getId(), "user001");
        var feedback = new InterviewAnalysisCallbackRequest.FeedbackPayload("feedback001", answer.getId(),
                "FEEDBACK_READY", "HIGH", "MEDIUM", "HIGH", "HIGH", "MEDIUM", List.of("evidence001"),
                List.of(), List.of(), "补充技术取舍。", "说明职责和取舍。", "已覆盖职责但缺少取舍。", 1);
        var feedbackCallback = new InterviewAnalysisCallbackRequest("4.0", "ANSWER_ANALYSIS", session.getId(),
                "revision001", "task001", analyzing.getVersion(), analyzing.getAttempt(), analyzing.getCallbackId(),
                analyzing.callbackTokenForTests(), "", "SUCCEEDED", java.util.UUID.randomUUID(), null, feedback, null)
                .withComputedPayloadHash();
        assertThat(fixture.service.acceptCallback(feedbackCallback).code()).isEqualTo("ACCEPTED");
        InterviewSession feedbackReady = fixture.service.getSession(session.getId(), "user001");

        assertThat(fixture.service.nextQuestionId(session.getId(), "user001")).isEqualTo("question003");
        InterviewSession next = fixture.service.moveToNextQuestion(session.getId(), "user001", feedbackReady.getVersion());

        assertThat(next.getState()).isEqualTo(InterviewSession.State.WAITING_FOR_ANSWER);
        assertThat(next.getCurrentQuestionId()).isEqualTo("question003");
        assertThat(fixture.service.getAnsweredQuestionIds(session.getId(), "user001")).containsExactly("question002");
    }

    @Test
    void dispatchesEachRequirementWithOnlyItsOwnEvidence() {
        Fixture fixture = fixtureWithTwoRequirements();
        InterviewSession session = fixture.service.create(new InterviewSessionService.CreateInterviewSessionCommand(
                "user001", UserRole.USER, "task001", "interview-create-key-0002"));

        var captured = org.mockito.ArgumentCaptor.forClass(PythonInterviewClient.InternalInterviewJob.class);
        verify(fixture.python).dispatch(captured.capture());
        @SuppressWarnings("unchecked")
        var requirements = (List<java.util.Map<String, Object>>) captured.getValue().questionGeneration().get("requirements");

        assertThat(requirements).hasSize(2);
        @SuppressWarnings("unchecked")
        var firstEvidence = (List<java.util.Map<String, Object>>) requirements.get(0).get("evidence");
        @SuppressWarnings("unchecked")
        var secondEvidence = (List<java.util.Map<String, Object>>) requirements.get(1).get("evidence");
        assertThat(firstEvidence).extracting(item -> item.get("evidenceId")).containsExactly("evidence001");
        assertThat(secondEvidence).extracting(item -> item.get("evidenceId")).containsExactly("evidence002");
        assertThat(session.getState()).isEqualTo(InterviewSession.State.QUESTION_GENERATING);
    }

    private static java.util.List<InterviewAnalysisCallbackRequest.QuestionPayload> questions() {
        return java.util.List.of(
                new InterviewAnalysisCallbackRequest.QuestionPayload("question001", 1, "BASIC_CONFIRMATION", "BASIC", "说明职责", "requirement001", "Java 服务开发", java.util.List.of("evidence001"), "核对职责", .9),
                new InterviewAnalysisCallbackRequest.QuestionPayload("question002", 2, "PROJECT_DEEP_DIVE", "INTERMEDIATE", "说明取舍", "requirement001", "Java 服务开发", java.util.List.of("evidence001"), "追问项目", .85),
                new InterviewAnalysisCallbackRequest.QuestionPayload("question003", 3, "JOB_SCENARIO", "ADVANCED", "说明排查", "requirement001", "Java 服务开发", java.util.List.of("evidence001"), "覆盖场景", .8),
                new InterviewAnalysisCallbackRequest.QuestionPayload("question004", 4, "SYNTHESIS_FOLLOW_UP", "ADVANCED", "联系岗位", "requirement001", "Java 服务开发", java.util.List.of("evidence001"), "综合表达", .75));
    }

    private static Fixture fixture() {
        String userId = "user001";
        String resumeId = "resume001";
        String revisionId = "revision001";
        String taskId = "task001";
        var resumes = mock(ResumeRepository.class);
        var revisions = new ResumeRevisionRepository.InMemory();
        var lifecycle = mock(ResumeLifecycleService.class);
        var profiles = mock(LlmProfileService.class);
        var tasks = mock(MatchTaskRepository.class);
        var results = mock(AnalysisResultRepository.class);
        var evidence = mock(AnalysisEvidenceRepository.class);
        var python = mock(PythonInterviewClient.class);
        var resume = mock(Resume.class);
        var task = new MatchTask(taskId, "callback001", resumeId, revisionId, "profile001", userId, 1,
                JobFamily.JAVA_BACKEND, "Build reliable Java services with clear tests and operational ownership.",
                "matching-key-0001", "token-token-token-token-token-token", java.util.Set.of("evidence001"),
                MatchTask.PublicationState.PUBLISHED, NOW);
        task.markSucceeded();
        var evidenceReference = new AnalysisCallbackRequest.EvidenceReference("evidence001", 0, 12,
                "Java services", .95);
        var requirement = new AnalysisCallbackRequest.RequirementMatch("requirement001", "Java 服务开发",
                "MANDATORY", "SATISFIED", "EXACT", "SKILLS", .9, List.of(evidenceReference),
                "HIGH", null, "SUPPORTED_FACT");
        var result = new AnalysisResult(taskId, resumeId, revisionId, 1, task.getJobDescriptionText(), null,
                List.of(requirement), List.of(), NOW, "SUCCEEDED", null);
        when(tasks.findByIdForUpdate(taskId)).thenReturn(java.util.Optional.of(task));
        when(lifecycle.lockActiveForRevision(resumeId, revisionId)).thenReturn(java.util.Optional.of(resume));
        when(resume.getOwnerId()).thenReturn(userId);
        when(resume.getId()).thenReturn(resumeId);
        when(resume.getEffectiveRevisionId()).thenReturn(revisionId);
        when(results.findByTaskId(taskId)).thenReturn(java.util.Optional.of(result));
        when(evidence.findByTaskId(taskId)).thenReturn(List.of(new AnalysisEvidence("evidence001", taskId, "TXT", "txt:0", 0, 12, "Java services")));
        when(profiles.decryptForDispatch(userId, "profile001"))
                .thenReturn(new DispatchLlmProfile(URI.create("http://127.0.0.1:9000"), "fixture-model", "fixture-key"));
        when(tasks.findByCreatorIdAndIdempotencyKey(userId, "matching-key-0001"))
                .thenReturn(java.util.Optional.of(task));
        return new Fixture(new InterviewSessionService(lifecycle, profiles, tasks, results, evidence,
                new InterviewSessionRepository.InMemory(), new InterviewQuestionRepository.InMemory(),
                new InterviewAnswerRepository.InMemory(), new InterviewFeedbackRepository.InMemory(),
                new InterviewConfirmationRepository.InMemory(), new InterviewCallbackReceiptRepository.InMemory(),
                new AesGcmCryptoService("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="),
                new com.resumethinking.platform.ids.InMemoryReadableIdGenerator(), python,
                Clock.fixed(NOW, ZoneOffset.UTC)), python, tasks, results, evidence);
    }

    private static Fixture fixtureWithTwoRequirements() {
        Fixture fixture = fixture();
        var task = new MatchTask("task001", "callback001", "resume001", "revision001", "profile001", "user001", 1,
                JobFamily.JAVA_BACKEND, "Build reliable Java services with clear tests and operational ownership.",
                "matching-key-0002", "token-token-token-token-token-token", java.util.Set.of("evidence001", "evidence002"),
                MatchTask.PublicationState.PUBLISHED, NOW);
        task.markSucceeded();
        var first = new AnalysisCallbackRequest.EvidenceReference("evidence001", 0, 12, "Java services", .95);
        var second = new AnalysisCallbackRequest.EvidenceReference("evidence002", 20, 34, "clear tests", .9);
        var firstRequirement = new AnalysisCallbackRequest.RequirementMatch("requirement001", "Java 服务开发",
                "MANDATORY", "SATISFIED", "EXACT", "SKILLS", .9, List.of(first), "HIGH", null, "SUPPORTED_FACT");
        var secondRequirement = new AnalysisCallbackRequest.RequirementMatch("requirement002", "测试与质量保障",
                "PREFERRED", "PARTIALLY_SATISFIED", "SEMANTIC", "PROJECT_EXPERIENCE", .7, List.of(second), "MEDIUM", "缺少测试细节", "NEEDS_USER_CONFIRMATION");
        var result = new AnalysisResult("task001", "resume001", "revision001", 1, task.getJobDescriptionText(), null,
                List.of(firstRequirement, secondRequirement), List.of(), NOW, "SUCCEEDED", null);
        when(fixture.tasks.findByIdForUpdate("task001")).thenReturn(java.util.Optional.of(task));
        when(fixture.results.findByTaskId("task001")).thenReturn(java.util.Optional.of(result));
        when(fixture.evidence.findByTaskId("task001")).thenReturn(List.of(
                new AnalysisEvidence("evidence001", "task001", "TXT", "txt:0", 0, 12, "Java services"),
                new AnalysisEvidence("evidence002", "task001", "TXT", "txt:20", 20, 34, "clear tests")));
        return fixture;
    }

    private record Fixture(InterviewSessionService service, PythonInterviewClient python,
                           MatchTaskRepository tasks, AnalysisResultRepository results,
                           AnalysisEvidenceRepository evidence) { }
}
