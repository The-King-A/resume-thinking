package com.resumethinking.platform.interviews;

import com.resumethinking.platform.matching.JobFamily;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

@Entity
@Table(name = "interview_sessions", uniqueConstraints = {
        @UniqueConstraint(name = "uq_interview_sessions_owner_key", columnNames = {"owner_id", "idempotency_key"})
})
public class InterviewSession {
    public enum State {
        QUESTION_GENERATING, WAITING_FOR_ANSWER, ANSWER_ANALYZING,
        FEEDBACK_READY, COMPLETED, FAILED, DELETED
    }

    public enum WorkType { QUESTION_GENERATION, ANSWER_ANALYSIS }

    @Id
    @Column(nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String id;
    @Column(name = "owner_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String ownerId;
    @Column(name = "resume_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String resumeId;
    @Column(name = "revision_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String revisionId;
    @Column(name = "match_task_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String matchTaskId;
    @Column(name = "llm_profile_id", nullable = false, length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String llmProfileId;
    @Enumerated(EnumType.STRING)
    @Column(name = "job_family", nullable = false, length = 32)
    private JobFamily jobFamily;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private State state;
    @Enumerated(EnumType.STRING)
    @Column(name = "work_type", length = 32)
    private WorkType workType;
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;
    @Column(name = "callback_id", length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String callbackId;
    @Column(name = "callback_token_hash", length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String callbackTokenHash;
    @Column(nullable = false)
    private int attempt;
    @Column(name = "session_version", nullable = false)
    private long sessionVersion;
    @Column(name = "question_count", nullable = false)
    private int questionCount;
    @Column(name = "current_question_id", length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String currentQuestionId;
    @Column(name = "current_answer_id", length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String currentAnswerId;
    @Column(name = "feedback_id", length = 64,
            columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin")
    private String feedbackId;
    @Column(name = "failure_code", length = 64)
    private String failureCode;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Column(name = "deleted_at")
    private Instant deletedAt;
    @Version
    @Column(name = "persistence_version", nullable = false)
    private long persistenceVersion;
    @Transient
    private String callbackTokenForTests;

    protected InterviewSession() {
    }

    private InterviewSession(String id, String ownerId, String resumeId, String revisionId,
                             String matchTaskId, String llmProfileId, JobFamily jobFamily,
                             String idempotencyKey, Instant now) {
        this.id = Objects.requireNonNull(id);
        this.ownerId = Objects.requireNonNull(ownerId);
        this.resumeId = Objects.requireNonNull(resumeId);
        this.revisionId = Objects.requireNonNull(revisionId);
        this.matchTaskId = Objects.requireNonNull(matchTaskId);
        this.llmProfileId = Objects.requireNonNull(llmProfileId);
        this.jobFamily = Objects.requireNonNull(jobFamily);
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey);
        this.state = State.QUESTION_GENERATING;
        this.attempt = 0;
        this.sessionVersion = 0;
        this.questionCount = 0;
        this.createdAt = Objects.requireNonNull(now);
        this.updatedAt = now;
    }

    public static InterviewSession create(String id, String ownerId, String resumeId, String revisionId,
                                          String matchTaskId, String llmProfileId, JobFamily jobFamily,
                                          String idempotencyKey, Instant now) {
        return new InterviewSession(id, ownerId, resumeId, revisionId, matchTaskId, llmProfileId,
                jobFamily, idempotencyKey, now);
    }

    public void startQuestionGeneration(String callbackId, String callbackToken, int attempt, Instant now) {
        requireState(State.QUESTION_GENERATING);
        startWork(WorkType.QUESTION_GENERATION, callbackId, callbackToken, attempt, now);
    }

    public void acceptQuestions(int count, Instant now) {
        acceptQuestions(count, null, now);
    }

    public void acceptQuestions(int count, String firstQuestionId, Instant now) {
        requireState(State.QUESTION_GENERATING);
        if (workType != WorkType.QUESTION_GENERATION || count != 4) {
            throw new IllegalStateException("exactly four questions are required");
        }
        if (firstQuestionId != null && firstQuestionId.isBlank()) {
            throw new IllegalArgumentException("first question id must not be blank");
        }
        state = State.WAITING_FOR_ANSWER;
        questionCount = count;
        currentQuestionId = firstQuestionId;
        clearCallback();
        bump(now);
    }

    public void restartQuestionGeneration(String callbackId, String callbackToken, int attempt, Instant now) {
        if ((state != State.FAILED && state != State.WAITING_FOR_ANSWER) || currentAnswerId != null) {
            throw new IllegalStateException("questions cannot be regenerated after an answer");
        }
        state = State.QUESTION_GENERATING;
        questionCount = 0;
        currentQuestionId = null;
        feedbackId = null;
        failureCode = null;
        startWork(WorkType.QUESTION_GENERATION, callbackId, callbackToken, attempt, now);
    }

    public void startAnswerAnalysis(String answerId, String callbackId, String callbackToken,
                                    int attempt, Instant now) {
        startAnswerAnalysis(answerId, null, callbackId, callbackToken, attempt, now);
    }

    public void startAnswerAnalysis(String answerId, String questionId, String callbackId, String callbackToken,
                                    int attempt, Instant now) {
        requireState(State.WAITING_FOR_ANSWER);
        this.currentAnswerId = Objects.requireNonNull(answerId);
        this.currentQuestionId = questionId;
        startWork(WorkType.ANSWER_ANALYSIS, callbackId, callbackToken, attempt, now);
    }

    public void acceptFeedback(String feedbackId, Instant now) {
        requireState(State.ANSWER_ANALYZING);
        if (workType != WorkType.ANSWER_ANALYSIS) throw new IllegalStateException("feedback work type mismatch");
        this.feedbackId = Objects.requireNonNull(feedbackId);
        state = State.FEEDBACK_READY;
        clearCallback();
        bump(now);
    }

    public void moveToNextQuestion(String nextQuestionId, Instant now) {
        if (state != State.FEEDBACK_READY || nextQuestionId == null || nextQuestionId.isBlank()) {
            throw new IllegalStateException("next question is not available");
        }
        currentQuestionId = nextQuestionId;
        currentAnswerId = null;
        feedbackId = null;
        workType = null;
        state = State.WAITING_FOR_ANSWER;
        failureCode = null;
        bump(now);
    }

    public void complete(Instant now) {
        if (state != State.FEEDBACK_READY) throw new IllegalStateException("feedback is not ready");
        state = State.COMPLETED;
        bump(now);
    }

    public void markFailed(String code, Instant now) {
        if (state == State.DELETED || state == State.COMPLETED) return;
        state = State.FAILED;
        failureCode = Objects.requireNonNull(code);
        clearCallback();
        bump(now);
    }

    public void delete(Instant now) {
        if (state == State.DELETED) return;
        state = State.DELETED;
        deletedAt = Objects.requireNonNull(now);
        clearCallback();
        bump(now);
    }

    public boolean acceptsCallback(String callbackId, int callbackAttempt, long expectedVersion) {
        return state != State.DELETED && state != State.COMPLETED
                && Objects.equals(this.callbackId, callbackId)
                && this.attempt == callbackAttempt
                && this.sessionVersion == expectedVersion;
    }

    public boolean tokenMatches(String token) {
        return callbackTokenHash != null && token != null
                && MessageDigest.isEqual(callbackTokenHash.getBytes(StandardCharsets.UTF_8),
                sha256(token).getBytes(StandardCharsets.UTF_8));
    }

    private void startWork(WorkType type, String callbackId, String callbackToken, int attempt, Instant now) {
        if (attempt < 1 || callbackId == null || callbackToken == null || callbackToken.length() < 32) {
            throw new IllegalArgumentException("invalid interview callback metadata");
        }
        this.workType = type;
        if (type == WorkType.ANSWER_ANALYSIS) {
            this.state = State.ANSWER_ANALYZING;
        }
        this.callbackId = callbackId;
        this.callbackTokenHash = sha256(callbackToken);
        this.callbackTokenForTests = callbackToken;
        this.attempt = attempt;
        bump(now);
    }

    private void clearCallback() {
        callbackId = null;
        callbackTokenHash = null;
        callbackTokenForTests = null;
        attempt = 0;
    }

    private void requireState(State expected) {
        if (state != expected) throw new IllegalStateException("invalid interview session state");
    }

    private void bump(Instant now) {
        sessionVersion++;
        updatedAt = Objects.requireNonNull(now);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    public String getId() { return id; }
    public String getOwnerId() { return ownerId; }
    public String getResumeId() { return resumeId; }
    public String getRevisionId() { return revisionId; }
    public String getMatchTaskId() { return matchTaskId; }
    public String getLlmProfileId() { return llmProfileId; }
    public JobFamily getJobFamily() { return jobFamily; }
    public State getState() { return state; }
    public WorkType getWorkType() { return workType; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getCallbackId() { return callbackId; }
    public int getAttempt() { return attempt; }
    public long getVersion() { return sessionVersion; }
    public int getQuestionCount() { return questionCount; }
    public String getCurrentQuestionId() { return currentQuestionId; }
    public String getCurrentAnswerId() { return currentAnswerId; }
    public String getFeedbackId() { return feedbackId; }
    public String getFailureCode() { return failureCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    String callbackTokenForTests() { return callbackTokenForTests; }
}
