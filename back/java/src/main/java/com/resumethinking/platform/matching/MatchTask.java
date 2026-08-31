package com.resumethinking.platform.matching;

import com.resumethinking.platform.resumes.VisibilityState;
import jakarta.persistence.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "analysis_tasks", uniqueConstraints = @UniqueConstraint(name = "uq_analysis_task_owner_key", columnNames = {"creator_id", "idempotency_key"}))
public class MatchTask {
    public enum State { QUEUED, PROCESSING, SUCCEEDED, FAILED, TIMED_OUT, BLOCKED }

    @Id @Column(nullable=false,length=64,columnDefinition="VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String id;
    @Column(name = "resume_id", nullable = false,length=64, columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String resumeId;
    @Column(name = "llm_profile_id", nullable = false,length=64, columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String llmProfileId;
    @Column(name = "creator_id", nullable = false,length=64, columnDefinition = "VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin") private String creatorId;
    @Column(name = "resume_version", nullable = false) private long resumeVersion;
    @Enumerated(EnumType.STRING) @Column(name = "job_family", nullable = false, length = 32) private JobFamily jobFamily;
    @Column(name = "job_description_text", nullable = false, columnDefinition = "MEDIUMTEXT") private String jobDescriptionText;
    @Column(name = "idempotency_key", nullable = false, length = 128) private String idempotencyKey;
    @Column(nullable = false) private int attempt;
    @Column(name = "callback_token_hash", nullable = false, length = 64) private String callbackTokenHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private State state;
    @Column(name = "failure_code", length = 64) private String failureCode;
    @Column(name = "result_available", nullable = false) private boolean resultAvailable;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;
    @Transient private String callbackToken;
    @Transient private Set<String> allowedEvidence = new LinkedHashSet<>();

    protected MatchTask() {}
    public MatchTask(String id, String resumeId, String llmProfileId, String creatorId, long resumeVersion,
                     JobFamily jobFamily, String jobDescriptionText, String idempotencyKey, String callbackToken,
                     Set<String> allowedEvidence, Instant now) {
        this.id = id; this.resumeId = resumeId; this.llmProfileId = llmProfileId; this.creatorId = creatorId;
        this.resumeVersion = resumeVersion; this.jobFamily = jobFamily; this.jobDescriptionText = jobDescriptionText; this.idempotencyKey = idempotencyKey;
        this.attempt = 1; this.callbackToken = callbackToken; this.callbackTokenHash = sha256(callbackToken);
        this.allowedEvidence = new LinkedHashSet<>(allowedEvidence); this.state = State.QUEUED; this.createdAt = now; this.updatedAt = now;
    }
    public MatchTask(String id, String resumeId, String llmProfileId, String creatorId, long resumeVersion,
                     String jobDescriptionText, String idempotencyKey, String callbackToken,
                     Set<String> allowedEvidence, Instant now) {
        this(id, resumeId, llmProfileId, creatorId, resumeVersion, JobFamily.JAVA_BACKEND,
                jobDescriptionText, idempotencyKey, callbackToken, allowedEvidence, now);
    }
    public void markProcessing() { if (state == State.QUEUED) { state = State.PROCESSING; updatedAt = Instant.now(); } }
    public void markSucceeded() { state = State.SUCCEEDED; resultAvailable = true; updatedAt = Instant.now(); }
    public void markFailed(String code) { state = State.FAILED; failureCode = code; updatedAt = Instant.now(); }
    public void markTimedOut(String code) { state = State.TIMED_OUT; failureCode = code; updatedAt = Instant.now(); }
    public void markBlocked() { state = State.BLOCKED; resultAvailable = false; failureCode = "TASK_GONE"; updatedAt = Instant.now(); }
    public boolean accepts(int callbackAttempt, String token, long currentResumeVersion, VisibilityState visibility) {
        return state != State.BLOCKED && visibility == VisibilityState.ACTIVE && callbackAttempt == attempt
                && resumeVersion == currentResumeVersion && MessageDigest.isEqual(callbackTokenHash.getBytes(StandardCharsets.UTF_8), sha256(token).getBytes(StandardCharsets.UTF_8));
    }
    public boolean tokenMatches(String token) { return MessageDigest.isEqual(callbackTokenHash.getBytes(StandardCharsets.UTF_8), sha256(token).getBytes(StandardCharsets.UTF_8)); }
    public boolean evidenceAllowed(String id) { return allowedEvidence.contains(id); }
    public String getId() { return id; } public String id() { return id; }
    public String getResumeId() { return resumeId; } public String resumeId() { return resumeId; }
    public String getLlmProfileId() { return llmProfileId; } public String llmProfileId() { return llmProfileId; }
    public String getCreatorId() { return creatorId; } public String creatorId() { return creatorId; }
    public long getResumeVersion() { return resumeVersion; } public long resumeVersion() { return resumeVersion; }
    public JobFamily getJobFamily() { return jobFamily; } public JobFamily jobFamily() { return jobFamily; }
    public String getJobDescriptionText() { return jobDescriptionText; } public String jobDescriptionText() { return jobDescriptionText; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getAttempt() { return attempt; } public int attempt() { return attempt; }
    public State getState() { return state; } public State state() { return state; }
    public String getFailureCode() { return failureCode; } public boolean isResultAvailable() { return resultAvailable; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
    public Set<String> getAllowedEvidence() { return Collections.unmodifiableSet(allowedEvidence); }
    public String callbackTokenForTests() { return callbackToken; }
    static String sha256(String value) { try { var md = MessageDigest.getInstance("SHA-256"); return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }
}
