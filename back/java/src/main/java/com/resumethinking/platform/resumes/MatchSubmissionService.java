package com.resumethinking.platform.resumes;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.crypto.AesGcmCryptoService;
import com.resumethinking.platform.ids.BusinessIdType;
import com.resumethinking.platform.ids.ReadableIdGenerator;
import com.resumethinking.platform.matching.AnalysisResult;
import com.resumethinking.platform.matching.AnalysisResultRepository;
import com.resumethinking.platform.matching.CreateMatchTaskCommand;
import com.resumethinking.platform.matching.IdempotencyConflictException;
import com.resumethinking.platform.matching.JobFamily;
import com.resumethinking.platform.matching.MatchTask;
import com.resumethinking.platform.matching.MatchTaskService;
import com.resumethinking.platform.profiles.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.HexFormat;

@Service
public class MatchSubmissionService {
    private static final int MAX_UPLOAD_BYTES = 5_000_000;
    private final ResumeRepository resumes;
    private final ResumeRevisionRepository revisions;
    private final MatchTaskService tasks;
    private final AnalysisResultRepository results;
    private final AesGcmCryptoService crypto;
    private final ReadableIdGenerator ids;
    private final Clock clock;
    private final ResumeLifecycleService lifecycle;
    private final PlatformTransactionManager transactionManager;

    public MatchSubmissionService(ResumeRepository resumes, ResumeRevisionRepository revisions,
                                  MatchTaskService tasks, AnalysisResultRepository results,
                                  AesGcmCryptoService crypto, ReadableIdGenerator ids, Clock clock) {
        this(resumes, revisions, tasks, results, crypto, ids, clock, null, null);
    }

    public MatchSubmissionService(ResumeRepository resumes, ResumeRevisionRepository revisions,
                                  MatchTaskService tasks, AnalysisResultRepository results,
                                  AesGcmCryptoService crypto, ReadableIdGenerator ids, Clock clock,
                                  ResumeLifecycleService lifecycle) {
        this(resumes, revisions, tasks, results, crypto, ids, clock, lifecycle, null);
    }

    @Autowired
    public MatchSubmissionService(ResumeRepository resumes, ResumeRevisionRepository revisions,
                                  MatchTaskService tasks, AnalysisResultRepository results,
                                  AesGcmCryptoService crypto, ReadableIdGenerator ids, Clock clock,
                                  ResumeLifecycleService lifecycle,
                                  PlatformTransactionManager transactionManager) {
        this.resumes = resumes; this.revisions = revisions; this.tasks = tasks; this.results = results;
        this.crypto = crypto; this.ids = ids; this.clock = clock; this.lifecycle = lifecycle;
        this.transactionManager = transactionManager;
    }

    public synchronized MatchTask submitInitial(byte[] document, String filename, String title,
                                                SubmissionCommand command) {
        validateCommand(command);
        DocumentInput input = document(document, filename);
        String safeTitle = title == null ? defaultTitle(filename) : validateTitle(title);
        String fingerprint = submissionFingerprint("INITIAL", null, null, safeTitle,
                input.sourceType(), input.bytes(), command);
        Optional<MatchTask> existing = tasks.findIdempotentSubmission(command.actorId(), command.idempotencyKey());
        if (existing.isPresent()) return requireEquivalent(existing.get(), fingerprint);
        try {
            return inWriteTransaction(() -> submitInitialInTransaction(input, safeTitle, command, fingerprint));
        } catch (RuntimeException failure) {
            return resolveSubmissionRace(command, fingerprint, failure);
        }
    }

    private MatchTask submitInitialInTransaction(DocumentInput input, String safeTitle,
                                                  SubmissionCommand command, String fingerprint) {
        Optional<MatchTask> existing = tasks.findIdempotentSubmission(command.actorId(), command.idempotencyKey());
        if (existing.isPresent()) return requireEquivalent(existing.get(), fingerprint);
        Instant now = clock.instant();
        String resumeId = ids.next(BusinessIdType.RESUME);
        Resume resume = new Resume(resumeId, command.actorId(), safeTitle, input.sourceType(), command.role(),
                new byte[0], now, "v1");
        resumes.save(resume);
        AesGcmCryptoService.EncryptedValue encrypted = crypto.encryptBytes(input.bytes());
        ResumeRevision revision = new ResumeRevision(ids.next(BusinessIdType.REVISION), resumeId, 1,
                safeTitle, ResumeLifecycleService.normalizeTitle(safeTitle), input.sourceType(), "v1",
                encrypted.ciphertext(), encrypted.nonce(), ResumeRevision.State.PENDING, now);
        revisions.save(revision);
        resume.stageRevision(revision, now);
        resumes.save(resume);
        return tasks.createRevisionTask(resume, revision, taskCommand(command, resumeId, fingerprint),
                MatchTask.PublicationState.PENDING);
    }

    public synchronized MatchTask rematch(String resumeId, String expectedEffectiveRevisionId,
                                          byte[] replacementDocument, String replacementFilename,
                                          String title, SubmissionCommand command) {
        validateCommand(command);
        validateId(BusinessIdType.RESUME, resumeId);
        validateId(BusinessIdType.REVISION, expectedEffectiveRevisionId);
        String requestedTitle = title == null ? null : validateTitle(title);
        DocumentInput replacement = replacementDocument == null ? null
                : document(replacementDocument, replacementFilename);
        String fingerprint = submissionFingerprint("REMATCH", resumeId, expectedEffectiveRevisionId,
                requestedTitle, replacement == null ? null : replacement.sourceType(),
                replacement == null ? null : replacement.bytes(), command);
        Optional<MatchTask> idempotent = tasks.findIdempotentSubmission(command.actorId(), command.idempotencyKey());
        if (idempotent.isPresent()) return requireEquivalent(idempotent.get(), fingerprint);
        try {
            return inWriteTransaction(() -> rematchInTransaction(resumeId, expectedEffectiveRevisionId,
                    replacement, requestedTitle, command, fingerprint));
        } catch (RuntimeException failure) {
            return resolveSubmissionRace(command, fingerprint, failure);
        }
    }

    private MatchTask rematchInTransaction(String resumeId, String expectedEffectiveRevisionId,
                                           DocumentInput replacement, String requestedTitle,
                                           SubmissionCommand command, String fingerprint) {
        Optional<MatchTask> idempotent = tasks.findIdempotentSubmission(command.actorId(), command.idempotencyKey());
        if (idempotent.isPresent()) return requireEquivalent(idempotent.get(), fingerprint);
        Resume resume = lockOwnedActive(resumeId, command.actorId(), command.role());
        if (!Objects.equals(expectedEffectiveRevisionId, resume.getEffectiveRevisionId())) {
            throw new VersionConflictException();
        }
        ResumeRevision effective = revisions.findById(expectedEffectiveRevisionId)
                .filter(revision -> revision.getResumeId().equals(resumeId)
                        && revision.getState() == ResumeRevision.State.EFFECTIVE)
                .orElseThrow(ResourceNotFoundException::new);
        String safeTitle = requestedTitle == null ? effective.getTitle() : requestedTitle;
        boolean titleChanged = !safeTitle.equals(effective.getTitle());
        boolean fileChanged = replacement != null;
        if (!titleChanged && !fileChanged) {
            return tasks.createRevisionTask(resume, effective, taskCommand(command, resumeId, fingerprint),
                    MatchTask.PublicationState.NOT_REQUESTED);
        }

        if (resume.getPendingRevisionId() != null) {
            revisions.findByIdForUpdate(resume.getPendingRevisionId()).ifPresent(candidate -> {
                tasks.blockRevisionTasks(candidate.getId());
                candidate.markSuperseded();
                revisions.save(candidate);
            });
        }
        Resume.SourceType sourceType = effective.getSourceType();
        byte[] ciphertext = effective.getCiphertext();
        byte[] nonce = effective.getNonce();
        if (fileChanged) {
            AesGcmCryptoService.EncryptedValue encrypted = crypto.encryptBytes(replacement.bytes());
            sourceType = replacement.sourceType(); ciphertext = encrypted.ciphertext(); nonce = encrypted.nonce();
        }
        long revisionNo = revisions.findLatestByResumeId(resumeId).map(ResumeRevision::getRevisionNo).orElse(0L) + 1;
        ResumeRevision candidate = new ResumeRevision(ids.next(BusinessIdType.REVISION), resumeId, revisionNo,
                safeTitle, ResumeLifecycleService.normalizeTitle(safeTitle), sourceType, "v1", ciphertext, nonce,
                ResumeRevision.State.PENDING, clock.instant());
        revisions.save(candidate);
        resume.stageRevision(candidate, clock.instant());
        resumes.save(resume);
        return tasks.createRevisionTask(resume, candidate, taskCommand(command, resumeId, fingerprint),
                MatchTask.PublicationState.PENDING);
    }

    @Transactional(readOnly = true)
    public ResumeMatchContext getMatchContext(String resumeId, String actorId, UserRole role) {
        Resume resume = findOwnedActive(resumeId, actorId, role);
        String selectedRevisionId = resume.getPendingRevisionId() != null
                ? resume.getPendingRevisionId() : resume.getEffectiveRevisionId();
        if (selectedRevisionId == null) throw new ResourceNotFoundException();
        ResumeRevision selected = revisions.findById(selectedRevisionId)
                .filter(revision -> revision.getResumeId().equals(resumeId)).orElseThrow(ResourceNotFoundException::new);
        String latestSuccessfulTaskId = resume.getEffectiveRevisionId() == null ? null
                : results.findLatestByResumeIdAndRevisionId(resumeId, resume.getEffectiveRevisionId())
                        .map(AnalysisResult::taskId).orElse(null);
        MatchTask selectedTask = resume.getPendingRevisionId() != null
                ? tasks.latestTaskForRevision(resumeId, selectedRevisionId).orElseThrow(ResourceNotFoundException::new)
                : tasks.findTask(latestSuccessfulTaskId).orElseThrow(ResourceNotFoundException::new);
        return new ResumeMatchContext(selected.getTitle(), resume.getEffectiveRevisionId(), resume.getPendingRevisionId(),
                latestSuccessfulTaskId, selectedTask.getLlmProfileId(), selectedTask.getJobDescriptionText());
    }

    @Transactional(readOnly = true)
    public Page<EffectiveResume> listEffective(String actorId, UserRole role, Pageable pageable) {
        if (lifecycle == null) throw new IllegalStateException("lifecycle unavailable");
        Pageable candidatePage = pageable.isUnpaged() ? pageable
                : org.springframework.data.domain.PageRequest.of(0, Integer.MAX_VALUE, pageable.getSort());
        Page<Resume> candidates = lifecycle.listActive(actorId, role, candidatePage);
        java.util.List<EffectiveResume> effective = new java.util.ArrayList<>();
        for (Resume resume : candidates) {
            results.findLatestByResumeIdAndRevisionId(resume.getId(), resume.getEffectiveRevisionId())
                    .ifPresent(result -> effective.add(new EffectiveResume(resume, result.taskId())));
        }
        if (pageable.isUnpaged()) return new PageImpl<>(effective);
        int from = (int) Math.min(pageable.getOffset(), effective.size());
        int to = Math.min(from + pageable.getPageSize(), effective.size());
        return new PageImpl<>(effective.subList(from, to), pageable, effective.size());
    }

    @Transactional
    public void softDelete(DeleteResumeCommand command) {
        if (lifecycle == null) throw new IllegalStateException("lifecycle unavailable");
        lifecycle.softDelete(command);
    }

    private Resume lockOwnedActive(String resumeId, String actorId, UserRole role) {
        return authorizeActive(resumes.findByIdForUpdate(resumeId), actorId, role);
    }

    private Resume findOwnedActive(String resumeId, String actorId, UserRole role) {
        return authorizeActive(resumes.findById(resumeId), actorId, role);
    }

    private Resume authorizeActive(Optional<Resume> candidate, String actorId, UserRole role) {
        Instant now = clock.instant();
        return candidate.filter(resume -> (role == UserRole.ADMIN || resume.getOwnerId().equals(actorId))
                        && resume.getVisibilityState() == VisibilityState.ACTIVE && resume.getStatus() == 0
                        && resume.getVisibleUntil() != null && resume.getVisibleUntil().isAfter(now))
                .orElseThrow(ResourceNotFoundException::new);
    }

    private static CreateMatchTaskCommand taskCommand(SubmissionCommand command, String resumeId,
                                                       String submissionFingerprint) {
        return new CreateMatchTaskCommand(command.actorId(), resumeId, command.llmProfileId(), command.jobFamily(),
                command.jobDescriptionText(), command.idempotencyKey(), command.role(), submissionFingerprint);
    }

    private static void validateCommand(SubmissionCommand command) {
        if (command == null || !ReadableIdGenerator.isValid(BusinessIdType.USER, command.actorId())
                || command.role() == null
                || !ReadableIdGenerator.isValid(BusinessIdType.PROFILE, command.llmProfileId())
                || command.jobFamily() != JobFamily.JAVA_BACKEND
                || command.jobDescriptionText() == null || command.jobDescriptionText().length() < 20
                || command.jobDescriptionText().length() > 20_000 || command.idempotencyKey() == null
                || command.idempotencyKey().isBlank() || command.idempotencyKey().length() < 16
                || command.idempotencyKey().length() > 128) {
            throw new IllegalArgumentException("VALIDATION_ERROR");
        }
    }

    private MatchTask resolveSubmissionRace(SubmissionCommand command, String fingerprint,
                                            RuntimeException failure) {
        if (findDataIntegrityViolation(failure) == null) throw failure;
        Optional<MatchTask> raced = inReadTransaction(() ->
                tasks.findIdempotentSubmission(command.actorId(), command.idempotencyKey()));
        if (raced.isEmpty()) throw failure;
        return requireEquivalent(raced.get(), fingerprint);
    }

    private static MatchTask requireEquivalent(MatchTask task, String fingerprint) {
        if (!fingerprint.equals(task.getSubmissionFingerprint())) throw new IdempotencyConflictException();
        return task;
    }

    private static DataIntegrityViolationException findDataIntegrityViolation(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof DataIntegrityViolationException integrity) return integrity;
        }
        return null;
    }

    private static String submissionFingerprint(String operation, String resumeId, String expectedRevisionId,
                                                String title, Resume.SourceType sourceType, byte[] document,
                                                SubmissionCommand command) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, operation);
            updateDigest(digest, resumeId);
            updateDigest(digest, expectedRevisionId);
            updateDigest(digest, title);
            updateDigest(digest, sourceType == null ? null : sourceType.name());
            updateDigest(digest, document);
            updateDigest(digest, command.llmProfileId());
            updateDigest(digest, command.jobFamily().name());
            updateDigest(digest, command.jobDescriptionText());
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void updateDigest(MessageDigest digest, String value) {
        updateDigest(digest, value == null ? null : value.getBytes(StandardCharsets.UTF_8));
    }

    private static void updateDigest(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value == null ? -1 : value.length).array());
        if (value != null) digest.update(value);
    }

    private static void validateId(BusinessIdType type, String value) {
        if (!ReadableIdGenerator.isValid(type, value)) throw new IllegalArgumentException("VALIDATION_ERROR");
    }

    private <T> T inWriteTransaction(java.util.function.Supplier<T> operation) {
        if (transactionManager == null) return operation.get();
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> operation.get());
    }

    private <T> T inReadTransaction(java.util.function.Supplier<T> operation) {
        if (transactionManager == null) return operation.get();
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setReadOnly(true);
        return template.execute(status -> operation.get());
    }

    private static DocumentInput document(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_UPLOAD_BYTES || filename == null) {
            throw new IllegalArgumentException(bytes != null && bytes.length > MAX_UPLOAD_BYTES
                    ? "PAYLOAD_TOO_LARGE" : "VALIDATION_ERROR");
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        Resume.SourceType type;
        if (lower.endsWith(".txt")) type = Resume.SourceType.TXT;
        else if (lower.endsWith(".docx")) type = Resume.SourceType.DOCX;
        else throw new IllegalArgumentException("UNSUPPORTED_FILE");
        if (type == Resume.SourceType.TXT) {
            String normalized = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
            bytes = normalized.getBytes(StandardCharsets.UTF_8);
        }
        return new DocumentInput(bytes, type);
    }

    private static String validateTitle(String title) {
        String value = title.trim();
        if (value.isBlank() || value.length() > 200) throw new IllegalArgumentException("VALIDATION_ERROR");
        return value;
    }

    private static String defaultTitle(String filename) {
        String value = filename == null ? "resume" : filename;
        int dot = value.lastIndexOf('.');
        if (dot > 0) value = value.substring(0, dot);
        return validateTitle(value.length() > 200 ? value.substring(0, 200) : value);
    }

    private record DocumentInput(byte[] bytes, Resume.SourceType sourceType) {}

    public record SubmissionCommand(String actorId, UserRole role, String llmProfileId, JobFamily jobFamily,
                                    String jobDescriptionText, String idempotencyKey) {}

    public record EffectiveResume(Resume resume, String latestSuccessfulTaskId) {}
}
