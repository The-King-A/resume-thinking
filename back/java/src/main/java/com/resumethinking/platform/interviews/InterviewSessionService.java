package com.resumethinking.platform.interviews;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.crypto.AesGcmCryptoService;
import com.resumethinking.platform.ids.BusinessIdType;
import com.resumethinking.platform.ids.ReadableIdGenerator;
import com.resumethinking.platform.matching.AnalysisEvidence;
import com.resumethinking.platform.matching.AnalysisEvidenceRepository;
import com.resumethinking.platform.matching.AnalysisResult;
import com.resumethinking.platform.matching.AnalysisResultRepository;
import com.resumethinking.platform.matching.JobFamily;
import com.resumethinking.platform.matching.MatchTask;
import com.resumethinking.platform.matching.MatchTaskRepository;
import com.resumethinking.platform.matching.ResumeTextRedactor;
import com.resumethinking.platform.profiles.DispatchLlmProfile;
import com.resumethinking.platform.profiles.LlmProfileService;
import com.resumethinking.platform.resumes.Resume;
import com.resumethinking.platform.resumes.ResumeLifecycleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
public class InterviewSessionService {
    private final ResumeLifecycleService lifecycle;
    private final LlmProfileService profiles;
    private final MatchTaskRepository tasks;
    private final AnalysisResultRepository results;
    private final AnalysisEvidenceRepository evidence;
    private final InterviewSessionRepository sessions;
    private final InterviewQuestionRepository questions;
    private final InterviewAnswerRepository answers;
    private final InterviewFeedbackRepository feedback;
    private final InterviewConfirmationRepository confirmations;
    private final InterviewCallbackReceiptRepository receipts;
    private final AesGcmCryptoService crypto;
    private final ReadableIdGenerator ids;
    private final PythonInterviewClient python;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public InterviewSessionService(ResumeLifecycleService lifecycle, LlmProfileService profiles,
                                   MatchTaskRepository tasks, AnalysisResultRepository results,
                                   AnalysisEvidenceRepository evidence, InterviewSessionRepository sessions,
                                   InterviewQuestionRepository questions, InterviewAnswerRepository answers,
                                   InterviewFeedbackRepository feedback, InterviewConfirmationRepository confirmations,
                                   InterviewCallbackReceiptRepository receipts, AesGcmCryptoService crypto,
                                   ReadableIdGenerator ids, PythonInterviewClient python, Clock clock) {
        this.lifecycle = lifecycle;
        this.profiles = profiles;
        this.tasks = tasks;
        this.results = results;
        this.evidence = evidence;
        this.sessions = sessions;
        this.questions = questions;
        this.answers = answers;
        this.feedback = feedback;
        this.confirmations = confirmations;
        this.receipts = receipts;
        this.crypto = crypto;
        this.ids = ids;
        this.python = python;
        this.clock = clock;
    }

    @Transactional
    public synchronized InterviewSession create(CreateInterviewSessionCommand command) {
        validateCreate(command);
        Optional<InterviewSession> existing = sessions.findByOwnerIdAndIdempotencyKey(command.actorId(), command.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().getMatchTaskId().equals(command.matchTaskId())) throw new InterviewAnswerConflictException();
            return existing.get();
        }

        MatchTask task = tasks.findByIdForUpdate(command.matchTaskId())
                .filter(value -> command.actorId().equals(value.getCreatorId())
                        && value.getState() == MatchTask.State.SUCCEEDED
                        && value.isResultAvailable()
                        && value.getRevisionId() != null
                        && (value.getPublicationState() == MatchTask.PublicationState.NOT_REQUESTED
                        || value.getPublicationState() == MatchTask.PublicationState.PUBLISHED))
                .orElseThrow(InterviewMatchNotReadyException::new);
        Resume resume = lifecycle.lockActiveForRevision(task.getResumeId(), task.getRevisionId())
                .filter(value -> command.actorId().equals(value.getOwnerId())
                        && task.getRevisionId().equals(value.getEffectiveRevisionId()))
                .orElseThrow(InterviewMatchNotReadyException::new);
        AnalysisResult result = results.findByTaskId(task.getId())
                .filter(value -> resume.getId().equals(value.resumeId())
                        && task.getRevisionId().equals(value.revisionId())
                        && "SUCCEEDED".equals(value.outcome()))
                .orElseThrow(InterviewMatchNotReadyException::new);
        List<AnalysisEvidence> allowedEvidence = evidence.findByTaskId(task.getId());
        if (allowedEvidence.isEmpty() || result.requirements() == null || result.requirements().isEmpty()) {
            throw new InterviewMatchNotReadyException();
        }
        DispatchLlmProfile profile = profiles.decryptForDispatch(command.actorId(), task.getLlmProfileId());

        Instant now = clock.instant();
        String callbackToken = randomToken();
        InterviewSession session = InterviewSession.create(ids.next(BusinessIdType.INTERVIEW_SESSION),
                command.actorId(), resume.getId(), task.getRevisionId(), task.getId(), task.getLlmProfileId(),
                JobFamily.JAVA_BACKEND, command.idempotencyKey(), now);
        String callbackId = ids.next(BusinessIdType.CALLBACK);
        session.startQuestionGeneration(callbackId, callbackToken, 1, now);
        sessions.saveAndFlush(session);
        try {
            python.dispatch(buildQuestionJob(session, task, result, allowedEvidence, profile, callbackId, callbackToken));
        } catch (RuntimeException failure) {
            String code = failure.getMessage() == null ? "PYTHON_SERVICE_UNAVAILABLE" : failure.getMessage();
            session.markFailed(code, clock.instant());
            sessions.saveAndFlush(session);
            throw failure;
        }
        return session;
    }

    @Transactional(readOnly = true)
    public InterviewSession getSession(String sessionId, String actorId) {
        InterviewSession session = sessions.findById(sessionId)
                .filter(value -> actorId.equals(value.getOwnerId()))
                .orElseThrow(InterviewSessionNotFoundException::new);
        if (session.getState() == InterviewSession.State.DELETED) throw new InterviewSessionGoneException();
        return session;
    }

    @Transactional(readOnly = true)
    public List<InterviewQuestion> getQuestions(String sessionId, String actorId) {
        InterviewSession session = getSession(sessionId, actorId);
        if (session.getState() == InterviewSession.State.QUESTION_GENERATING) {
            throw new InterviewQuestionNotReadyException();
        }
        if (session.getState() == InterviewSession.State.FAILED) throw new InterviewQuestionNotReadyException();
        return questions.findBySessionIdOrderBySequenceNo(sessionId);
    }

    @Transactional(readOnly = true)
    public Set<String> getAnsweredQuestionIds(String sessionId, String actorId) {
        getSession(sessionId, actorId);
        Set<String> answered = new LinkedHashSet<>();
        for (InterviewQuestion question : questions.findBySessionIdOrderBySequenceNo(sessionId)) {
            if (answers.findBySessionIdAndQuestionId(sessionId, question.getId()).isPresent()) {
                answered.add(question.getId());
            }
        }
        return Set.copyOf(answered);
    }

    @Transactional
    public InterviewAnswer submitAnswer(String sessionId, String actorId, SubmitInterviewAnswerCommand command) {
        if (command == null || command.questionId() == null || !command.questionId().matches("^question[0-9]{3,}$")
                || command.answerText() == null || command.answerText().isBlank() || command.answerText().length() > 8000
                || command.idempotencyKey() == null || command.idempotencyKey().length() < 16
                || command.idempotencyKey().length() > 128) throw new IllegalArgumentException("VALIDATION_ERROR");
        InterviewSession session = sessions.findByIdForUpdate(sessionId)
                .filter(value -> actorId.equals(value.getOwnerId()))
                .orElseThrow(InterviewSessionNotFoundException::new);
        if (session.getState() == InterviewSession.State.DELETED) throw new InterviewSessionGoneException();
        Optional<InterviewAnswer> duplicate = answers.findBySessionIdAndIdempotencyKey(sessionId, command.idempotencyKey());
        if (duplicate.isPresent()) return duplicate.get();
        if (session.getState() != InterviewSession.State.WAITING_FOR_ANSWER
                || session.getVersion() != command.expectedSessionVersion()) throw new InterviewAnswerConflictException();
        InterviewQuestion question = questions.findById(command.questionId())
                .filter(value -> sessionId.equals(value.getSessionId()))
                .orElseThrow(InterviewSessionNotFoundException::new);
        if (answers.findBySessionIdAndQuestionId(sessionId, question.getId()).isPresent()) throw new InterviewAnswerConflictException();
        AesGcmCryptoService.EncryptedValue encrypted = crypto.encrypt(command.answerText());
        InterviewAnswer answer = new InterviewAnswer(ids.next(BusinessIdType.INTERVIEW_ANSWER), sessionId, question.getId(),
                command.idempotencyKey(), sha256(command.answerText()), encrypted.ciphertext(), encrypted.nonce(), clock.instant());
        answers.save(answer);
        MatchTask task = tasks.findByIdForUpdate(session.getMatchTaskId()).orElseThrow(InterviewSessionGoneException::new);
        DispatchLlmProfile profile = profiles.decryptForDispatch(actorId, session.getLlmProfileId());
        String callbackToken = randomToken();
        String callbackId = ids.next(BusinessIdType.CALLBACK);
        session.startAnswerAnalysis(answer.getId(), question.getId(), callbackId, callbackToken, 1, clock.instant());
        sessions.saveAndFlush(session);
        try {
            python.dispatch(buildAnswerJob(session, task, question, answer, command.answerText(), profile, callbackId, callbackToken));
        } catch (RuntimeException failure) {
            session.markFailed(safeDispatchFailureCode(failure), clock.instant());
            sessions.saveAndFlush(session);
            throw failure;
        }
        return answer;
    }

    @Transactional
    public InterviewSession regenerateQuestions(String sessionId, String actorId, long expectedVersion, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("VALIDATION_ERROR");
        }
        InterviewSession session = sessions.findByIdForUpdate(sessionId)
                .filter(value -> actorId.equals(value.getOwnerId())).orElseThrow(InterviewSessionNotFoundException::new);
        if (session.getState() == InterviewSession.State.DELETED) throw new InterviewSessionGoneException();
        if (session.getVersion() != expectedVersion || session.getCurrentAnswerId() != null) throw new InterviewAnswerConflictException();
        MatchTask task = tasks.findByIdForUpdate(session.getMatchTaskId()).orElseThrow(InterviewSessionGoneException::new);
        AnalysisResult result = results.findByTaskId(task.getId()).orElseThrow(InterviewMatchNotReadyException::new);
        List<AnalysisEvidence> allowedEvidence = evidence.findByTaskId(task.getId());
        if (allowedEvidence.isEmpty()) throw new InterviewMatchNotReadyException();
        DispatchLlmProfile profile = profiles.decryptForDispatch(actorId, session.getLlmProfileId());
        questions.deleteBySessionId(sessionId);
        String callbackToken = randomToken();
        String callbackId = ids.next(BusinessIdType.CALLBACK);
        session.restartQuestionGeneration(callbackId, callbackToken, 1, clock.instant());
        sessions.saveAndFlush(session);
        try {
            python.dispatch(buildQuestionJob(session, task, result, allowedEvidence, profile, callbackId, callbackToken));
        } catch (RuntimeException failure) {
            session.markFailed(safeDispatchFailureCode(failure), clock.instant());
            sessions.saveAndFlush(session);
            throw failure;
        }
        return session;
    }

    @Transactional(readOnly = true)
    public InterviewFeedback getFeedback(String sessionId, String actorId) {
        InterviewSession session = getSession(sessionId, actorId);
        if (session.getFeedbackId() == null) throw new InterviewFeedbackNotReadyException();
        return feedback.findById(session.getFeedbackId()).orElseThrow(InterviewFeedbackNotReadyException::new);
    }

    @Transactional(readOnly = true)
    public String getSubmittedAnswer(String sessionId, String actorId, String answerId) {
        InterviewSession session = getSession(sessionId, actorId);
        InterviewAnswer answer = answers.findById(answerId)
                .filter(value -> sessionId.equals(value.getSessionId())
                        && value.getState() != InterviewAnswer.State.CLEARED)
                .orElseThrow(InterviewFeedbackNotReadyException::new);
        return crypto.decrypt(answer.getCiphertext(), answer.getNonce());
    }

    @Transactional(readOnly = true)
    public String nextQuestionId(String sessionId, String actorId) {
        InterviewSession session = getSession(sessionId, actorId);
        if (session.getState() != InterviewSession.State.FEEDBACK_READY) return null;
        return nextUnansweredQuestionId(sessionId, session.getCurrentQuestionId());
    }

    @Transactional
    public InterviewSession moveToNextQuestion(String sessionId, String actorId, long expectedVersion) {
        InterviewSession session = sessions.findByIdForUpdate(sessionId)
                .filter(value -> actorId.equals(value.getOwnerId())).orElseThrow(InterviewSessionNotFoundException::new);
        if (session.getState() == InterviewSession.State.DELETED) throw new InterviewSessionGoneException();
        if (session.getVersion() != expectedVersion || session.getState() != InterviewSession.State.FEEDBACK_READY) {
            throw new InterviewAnswerConflictException();
        }
        String next = nextUnansweredQuestionId(sessionId, session.getCurrentQuestionId());
        if (next == null) {
            session.complete(clock.instant());
        } else {
            session.moveToNextQuestion(next, clock.instant());
        }
        sessions.saveAndFlush(session);
        return session;
    }

    private String nextUnansweredQuestionId(String sessionId, String currentQuestionId) {
        List<InterviewQuestion> available = questions.findBySessionIdOrderBySequenceNo(sessionId);
        if (available.isEmpty()) return null;
        int currentIndex = -1;
        for (int index = 0; index < available.size(); index++) {
            if (Objects.equals(available.get(index).getId(), currentQuestionId)) {
                currentIndex = index;
                break;
            }
        }
        for (int offset = 1; offset <= available.size(); offset++) {
            InterviewQuestion candidate = available.get(Math.floorMod(currentIndex + offset, available.size()));
            if (answers.findBySessionIdAndQuestionId(sessionId, candidate.getId()).isEmpty()) {
                return candidate.getId();
            }
        }
        return null;
    }

    @Transactional
    public InterviewConfirmation confirm(String sessionId, String actorId, String claimId, String decision, long expectedFeedbackVersion) {
        if (claimId == null || !claimId.matches("^claim[0-9]{3,}$")
                || !Set.of("CONFIRMED", "REJECTED").contains(decision)) throw new IllegalArgumentException("VALIDATION_ERROR");
        InterviewSession session = sessions.findByIdForUpdate(sessionId)
                .filter(value -> actorId.equals(value.getOwnerId())).orElseThrow(InterviewSessionNotFoundException::new);
        if (session.getState() == InterviewSession.State.DELETED) throw new InterviewSessionGoneException();
        InterviewFeedback current = getFeedback(sessionId, actorId);
        if (current.getFeedbackVersion() != expectedFeedbackVersion) throw new InterviewAnswerConflictException();
        Optional<InterviewConfirmation> existing = confirmations.findByFeedbackIdAndClaimId(current.getId(), claimId);
        if (existing.isPresent()) return existing.get();
        if (!feedbackHasClaim(current, claimId)) throw new InterviewSessionNotFoundException();
        InterviewConfirmation confirmation = new InterviewConfirmation(ids.next(BusinessIdType.INTERVIEW_CONFIRMATION),
                sessionId, current.getId(), claimId, decision, actorId, clock.instant());
        return confirmations.save(confirmation);
    }

    @Transactional
    public void delete(String sessionId, String actorId) {
        InterviewSession session = sessions.findByIdForUpdate(sessionId)
                .filter(value -> actorId.equals(value.getOwnerId())).orElseThrow(InterviewSessionNotFoundException::new);
        if (session.getState() == InterviewSession.State.DELETED) return;
        session.delete(clock.instant());
        sessions.saveAndFlush(session);
        confirmations.deleteBySessionId(sessionId);
        feedback.deleteBySessionId(sessionId);
        answers.deleteBySessionId(sessionId);
        questions.deleteBySessionId(sessionId);
    }

    @Transactional
    public CallbackResponse acceptCallback(InterviewAnalysisCallbackRequest request) {
        if (!validCallbackEnvelope(request)
                || !Objects.equals(request.payloadHash(), InterviewCallbackPayloadHash.compute(request))) {
            return new CallbackResponse("VALIDATION_ERROR", false);
        }
        InterviewSession session = sessions.findByIdForUpdate(request.sessionId()).orElse(null);
        if (session == null) return new CallbackResponse("INTERVIEW_SESSION_NOT_FOUND", false);
        if (session.getState() == InterviewSession.State.DELETED) {
            return new CallbackResponse("INTERVIEW_SESSION_GONE", false);
        }
        Optional<InterviewCallbackReceipt> previous = receipts.findByCallbackId(request.callbackId());
        if (previous.isPresent()) {
            return previous.get().payloadHash().equals(request.payloadHash())
                    ? new CallbackResponse("ACCEPTED_REPLAY", true)
                    : new CallbackResponse("IDEMPOTENCY_CONFLICT", false);
        }
        if (!session.acceptsCallback(request.callbackId(), request.attempt(), request.sessionVersion())
                || !session.tokenMatches(request.callbackToken())) {
            return new CallbackResponse("INTERVIEW_CALLBACK_STALE", false);
        }
        if (!Objects.equals(session.getRevisionId(), request.revisionId())
                || !Objects.equals(session.getMatchTaskId(), request.matchTaskId())) {
            return new CallbackResponse("VALIDATION_ERROR", false);
        }
        if (!"SUCCEEDED".equals(request.outcome())) {
            session.markFailed(request.errorCode() == null ? "INTERVIEW_MODEL_OUTPUT_INVALID" : request.errorCode(), clock.instant());
            sessions.saveAndFlush(session);
            receipts.save(new InterviewCallbackReceipt(request.callbackId(), request.payloadHash(), clock.instant()));
            return new CallbackResponse("ACCEPTED", true);
        }
        Set<String> allowedEvidence = new HashSet<>();
        for (AnalysisEvidence item : evidence.findByTaskId(session.getMatchTaskId())) allowedEvidence.add(item.getId());
        try {
            if ("QUESTION_GENERATION".equals(request.workType())) {
                acceptQuestionsCallback(session, request, allowedEvidence);
            } else if ("ANSWER_ANALYSIS".equals(request.workType())) {
                acceptFeedbackCallback(session, request, allowedEvidence);
            } else {
                return new CallbackResponse("VALIDATION_ERROR", false);
            }
        } catch (RuntimeException invalid) {
            questions.deleteBySessionId(session.getId());
            session.markFailed("INTERVIEW_MODEL_OUTPUT_INVALID", clock.instant());
            sessions.saveAndFlush(session);
            receipts.save(new InterviewCallbackReceipt(request.callbackId(), request.payloadHash(), clock.instant()));
            return new CallbackResponse("INTERVIEW_MODEL_OUTPUT_INVALID", false);
        }
        receipts.save(new InterviewCallbackReceipt(request.callbackId(), request.payloadHash(), clock.instant()));
        sessions.saveAndFlush(session);
        return new CallbackResponse("ACCEPTED", true);
    }

    private void acceptQuestionsCallback(InterviewSession session, InterviewAnalysisCallbackRequest request,
                                         Set<String> allowedEvidence) {
        if (session.getState() != InterviewSession.State.QUESTION_GENERATING
                || request.questions() == null || request.questions().size() != 4) throw new IllegalArgumentException();
        Set<String> types = new HashSet<>();
        Instant now = clock.instant();
        String firstQuestionId = null;
        for (InterviewAnalysisCallbackRequest.QuestionPayload question : request.questions()) {
            if (!types.add(question.questionType()) || question.evidenceIds() == null
                    || question.evidenceIds().stream().anyMatch(id -> !allowedEvidence.contains(id))) throw new IllegalArgumentException();
            try {
                String questionId = ids.next(BusinessIdType.INTERVIEW_QUESTION);
                questions.save(new InterviewQuestion(questionId, session.getId(), question.sequence(),
                        question.questionType(), question.difficulty(), question.questionText(), question.requirementId(),
                        question.requirementText(), mapper.writeValueAsString(question.evidenceIds()),
                        question.generationReason(), question.confidence(), now));
                if (question.sequence() == 1) firstQuestionId = questionId;
            } catch (Exception exception) {
                throw new IllegalArgumentException(exception);
            }
        }
        if (!types.containsAll(Set.of("BASIC_CONFIRMATION", "PROJECT_DEEP_DIVE", "JOB_SCENARIO", "SYNTHESIS_FOLLOW_UP"))) {
            throw new IllegalArgumentException();
        }
        session.acceptQuestions(4, firstQuestionId, now);
    }

    private void acceptFeedbackCallback(InterviewSession session, InterviewAnalysisCallbackRequest request,
                                        Set<String> allowedEvidence) {
        if (session.getState() != InterviewSession.State.ANSWER_ANALYZING || request.feedback() == null
                || !Objects.equals(session.getCurrentAnswerId(), request.feedback().answerId())) throw new IllegalArgumentException();
        if (request.feedback().evidenceIds() == null
                || request.feedback().evidenceIds().stream().anyMatch(id -> !allowedEvidence.contains(id))) throw new IllegalArgumentException();
        if (request.feedback().claims() == null || request.feedback().claims().stream().anyMatch(InterviewAnalysisCallbackRequest.Claim::applied)) {
            throw new IllegalArgumentException();
        }
        String feedbackId = ids.next(BusinessIdType.INTERVIEW_FEEDBACK);
        try {
            feedback.save(new InterviewFeedback(feedbackId, session.getId(),
                    request.feedback().answerId(), mapper.writeValueAsString(request.feedback()),
                    request.feedback().version(), clock.instant()));
        } catch (Exception exception) {
            throw new IllegalArgumentException(exception);
        }
        answers.findById(request.feedback().answerId()).ifPresent(InterviewAnswer::markFeedbackReady);
        session.acceptFeedback(feedbackId, clock.instant());
    }

    private static boolean validCallbackEnvelope(InterviewAnalysisCallbackRequest request) {
        return request != null && "4.0".equals(request.contractVersion())
                && request.sessionId() != null && request.revisionId() != null && request.matchTaskId() != null
                && request.callbackId() != null && request.callbackToken() != null && request.callbackToken().length() >= 32
                && request.payloadHash() != null && request.payloadHash().matches("[a-f0-9]{64}")
                && request.attempt() >= 1 && request.sessionVersion() >= 0 && request.correlationId() != null;
    }

    private PythonInterviewClient.InternalInterviewJob buildQuestionJob(InterviewSession session, MatchTask task,
                                                                         AnalysisResult result, List<AnalysisEvidence> allowed,
                                                                         DispatchLlmProfile profile, String callbackId,
                                                                         String callbackToken) {
        Map<String, Map<String, Object>> evidenceById = new LinkedHashMap<>();
        for (AnalysisEvidence item : allowed) {
            evidenceById.put(item.getId(), evidenceContext(item));
        }
        List<Map<String, Object>> requirements = new ArrayList<>();
        for (Object raw : result.requirements()) {
            Map<String, Object> value = mapper.convertValue(raw, new TypeReference<>() { });
            Map<String, Object> requirement = new LinkedHashMap<>();
            requirement.put("requirementId", String.valueOf(value.get("requirementId")));
            requirement.put("requirementText", String.valueOf(value.get("jobRequirementText")));
            requirement.put("requirementType", String.valueOf(value.get("requirementType")));
            requirement.put("matchStatus", String.valueOf(value.get("matchStatus")));
            requirement.put("gap", value.get("gap"));
            List<Map<String, Object>> requirementEvidence = new ArrayList<>();
            Object rawEvidence = value.get("evidence");
            if (rawEvidence instanceof List<?> references) {
                for (Object reference : references) {
                    if (reference instanceof Map<?, ?> evidenceReference) {
                        Object evidenceId = evidenceReference.get("evidenceId");
                        Map<String, Object> context = evidenceById.get(String.valueOf(evidenceId));
                        if (context != null) requirementEvidence.add(context);
                    }
                }
            }
            requirement.put("evidence", requirementEvidence);
            requirements.add(requirement);
        }
        URI callbackUrl = URI.create(System.getProperty("interview.callback-url",
                System.getenv().getOrDefault("INTERVIEW_CALLBACK_URL", "http://127.0.0.1:8080/internal/v4/interview-results")));
        return new PythonInterviewClient.InternalInterviewJob("4.0", "QUESTION_GENERATION", session.getId(),
                session.getRevisionId(), task.getId(), session.getVersion(), session.getAttempt(), callbackId,
                callbackUrl, callbackToken, true,
                new PythonInterviewClient.Provider(profile.baseUrl(), profile.model(), profile.apiKey()),
                Map.of("requirements", requirements,
                        "questionTypes", List.of("BASIC_CONFIRMATION", "PROJECT_DEEP_DIVE", "JOB_SCENARIO", "SYNTHESIS_FOLLOW_UP"),
                         "maxQuestions", 4), null, UUID.randomUUID());
    }

    private Map<String, Object> evidenceContext(AnalysisEvidence item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("evidenceId", item.getId());
        value.put("sourceLocation", item.getSourceLocation());
        value.put("sourceStart", item.getSourceStart());
        value.put("sourceEnd", item.getSourceEnd());
        value.put("excerpt", item.getSourceExcerpt() == null ? "" : item.getSourceExcerpt());
        value.put("strength", "MEDIUM");
        return value;
    }

    private PythonInterviewClient.InternalInterviewJob buildAnswerJob(InterviewSession session, MatchTask task,
                                                                       InterviewQuestion question, InterviewAnswer answer,
                                                                       String answerText, DispatchLlmProfile profile,
                                                                       String callbackId, String callbackToken) {
        List<AnalysisEvidence> source = evidence.findByTaskId(task.getId());
        List<Map<String, Object>> evidenceContext = source.stream().map(this::evidenceContext).toList();
        Map<String, Object> questionContext = new LinkedHashMap<>();
        questionContext.put("questionId", question.getId());
        questionContext.put("questionType", question.getQuestionType());
        questionContext.put("questionText", question.getQuestionText());
        questionContext.put("requirementId", question.getRequirementId());
        questionContext.put("requirementText", question.getRequirementText());
        questionContext.put("evidenceIds", readEvidenceIds(question.getEvidenceIdsJson()));
        Map<String, Object> requirement = new LinkedHashMap<>();
        requirement.put("requirementId", question.getRequirementId());
        requirement.put("requirementText", question.getRequirementText());
        requirement.put("requirementType", "MANDATORY");
        requirement.put("matchStatus", "PARTIALLY_SATISFIED");
        requirement.put("gap", null);
        requirement.put("evidence", evidenceContext);
        Map<String, Object> answerAnalysis = new LinkedHashMap<>();
        answerAnalysis.put("answerId", answer.getId());
        answerAnalysis.put("answerText", ResumeTextRedactor.redactedText(answerText));
        answerAnalysis.put("question", questionContext);
        answerAnalysis.put("requirement", requirement);
        answerAnalysis.put("evidence", evidenceContext);
        URI callbackUrl = URI.create(System.getProperty("interview.callback-url",
                System.getenv().getOrDefault("INTERVIEW_CALLBACK_URL", "http://127.0.0.1:8080/internal/v4/interview-results")));
        return new PythonInterviewClient.InternalInterviewJob("4.0", "ANSWER_ANALYSIS", session.getId(),
                session.getRevisionId(), task.getId(), session.getVersion(), session.getAttempt(), callbackId,
                callbackUrl, callbackToken, true,
                new PythonInterviewClient.Provider(profile.baseUrl(), profile.model(), profile.apiKey()),
                null, answerAnalysis, UUID.randomUUID());
    }

    private List<String> readEvidenceIds(String json) {
        try { return mapper.readValue(json, new TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }

    private boolean feedbackHasClaim(InterviewFeedback value, String claimId) {
        try {
            Map<String, Object> payload = mapper.readValue(value.getPayloadJson(), new TypeReference<>() { });
            Object claims = payload.get("claims");
            if (!(claims instanceof List<?> values)) return false;
            return values.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .anyMatch(claim -> claimId.equals(claim.get("id")));
        } catch (Exception ignored) { return false; }
    }

    private static void validateCreate(CreateInterviewSessionCommand command) {
        if (command == null || command.actorId() == null || command.actorId().isBlank()
                || command.role() == null || command.matchTaskId() == null
                || !command.matchTaskId().matches("^task[0-9]{3,}$")
                || command.idempotencyKey() == null || command.idempotencyKey().length() < 16
                || command.idempotencyKey().length() > 128) {
            throw new IllegalArgumentException("VALIDATION_ERROR");
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[48];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private static String safeDispatchFailureCode(RuntimeException failure) {
        return "PYTHON_SERVICE_AUTHENTICATION_FAILED".equals(failure.getMessage())
                ? "PYTHON_SERVICE_AUTHENTICATION_FAILED" : "PYTHON_SERVICE_UNAVAILABLE";
    }

    public record CreateInterviewSessionCommand(String actorId, UserRole role, String matchTaskId, String idempotencyKey) { }
    public record SubmitInterviewAnswerCommand(String questionId, String answerText, long expectedSessionVersion, String idempotencyKey) { }
    public record CallbackResponse(String code, boolean accepted) { }
}
