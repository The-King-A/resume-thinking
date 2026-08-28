package com.resumethinking.platform.matching;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.crypto.AesGcmCryptoService;
import com.resumethinking.platform.profiles.*;
import com.resumethinking.platform.resumes.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;
import java.time.Instant;
import java.util.*;

@Service
public class MatchTaskService {
    // DOCX is a ZIP container. Bound both the metadata surface and the
    // uncompressed bytes consumed from it so a tiny compressed payload cannot
    // force an unbounded allocation or decompression pass.
    private static final int MAX_DOCX_ENTRY_COUNT = 256;
    private static final long MAX_DOCX_ENTRY_UNCOMPRESSED_BYTES = 8L * 1024 * 1024;
    private static final long MAX_DOCX_ENTRY_COMPRESSED_BYTES = 8L * 1024 * 1024;
    private static final long MAX_DOCX_XML_BYTES = 8L * 1024 * 1024;
    private static final long MAX_DOCX_TOTAL_UNCOMPRESSED_BYTES = 16L * 1024 * 1024;
    private static final int DOCX_READ_BUFFER_BYTES = 8192;

    private final ResumeLifecycleService lifecycle;
    private final LlmProfileService profiles;
    private final MatchTaskRepository tasks;
    private final PythonAnalysisClient python;
    private final AnalysisResultRepository results;
    private final CallbackReceiptRepository receipts;
    private final AnalysisEvidenceRepository evidenceRepository;
    private final AesGcmCryptoService crypto;
    private final DispatchFailureRecorder dispatchFailures;
    private final PlatformTransactionManager transactionManager;
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python) {
        this(lifecycle, profiles, tasks, python, new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), null, null, null);
    }
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python, AnalysisResultRepository results) {
        this(lifecycle, profiles, tasks, python, results, new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), null, null, null);
    }
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python, AnalysisResultRepository results, CallbackReceiptRepository receipts) {
        this(lifecycle, profiles, tasks, python, results, receipts, new AnalysisEvidenceRepository.InMemory(), null, null, null);
    }
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python, AnalysisResultRepository results, CallbackReceiptRepository receipts, AnalysisEvidenceRepository evidenceRepository) {
        this(lifecycle, profiles, tasks, python, results, receipts, evidenceRepository, null, null, null);
    }
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python, AnalysisResultRepository results, CallbackReceiptRepository receipts, AnalysisEvidenceRepository evidenceRepository, AesGcmCryptoService crypto) {
        this(lifecycle, profiles, tasks, python, results, receipts, evidenceRepository, crypto, null, null);
    }
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python, AnalysisResultRepository results, CallbackReceiptRepository receipts, AnalysisEvidenceRepository evidenceRepository, AesGcmCryptoService crypto, DispatchFailureRecorder dispatchFailures) {
        this(lifecycle, profiles, tasks, python, results, receipts, evidenceRepository, crypto, dispatchFailures, null);
    }
    @Autowired
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python, AnalysisResultRepository results, CallbackReceiptRepository receipts, AnalysisEvidenceRepository evidenceRepository, AesGcmCryptoService crypto, DispatchFailureRecorder dispatchFailures, PlatformTransactionManager transactionManager) {
        this.lifecycle = lifecycle; this.profiles = profiles; this.tasks = tasks; this.python = python; this.results = results; this.receipts = receipts; this.evidenceRepository = evidenceRepository; this.crypto = crypto; this.dispatchFailures = dispatchFailures; this.transactionManager = transactionManager;
    }

    /**
     * Execute task creation in its own transaction.  A unique-key race is
     * resolved only after that transaction has rolled back, using a fresh
     * read transaction so an aborted persistence context cannot be reused.
     */
    public synchronized MatchTask createTask(CreateMatchTaskCommand command) {
        validateCreateCommand(command);
        try {
            return inWriteTransaction(() -> createTaskInTransaction(command));
        } catch (RuntimeException failure) {
            DataIntegrityViolationException duplicate = findDataIntegrityViolation(failure);
            if (duplicate != null) return resolveTaskRace(command, duplicate);
            throw failure;
        }
    }

    private MatchTask createTaskInTransaction(CreateMatchTaskCommand command) {
        var existing = tasks.findByCreatorIdAndIdempotencyKey(command.actorId(), command.idempotencyKey());
        if (existing.isPresent()) {
            if (!sameSubmission(existing.get(), command)) throw new IdempotencyConflictException();
            return existing.get();
        }
        ResumeAnalysisReservation reservation = lifecycle.reserveForAnalysis(command.resumeId(), command.actorId(), command.role() == null ? UserRole.USER : command.role());
        DispatchLlmProfile profile = profiles == null ? null : profiles.decryptForDispatch(command.actorId(), command.llmProfileId());
        String callbackToken = randomToken();
        Resume resume = lifecycle.findActiveForAnalysis(reservation.resumeId(), reservation.resumeVersion()).orElseThrow(ResourceNotFoundException::new);
        byte[] documentBytes;
        // Raw resume content is always persisted as ciphertext.  Missing
        // crypto configuration or an invalid ciphertext must fail closed: an
        // encrypted blob is never a valid fallback analysis document.
        if (resume.getRawContentNonce() == null || crypto == null) {
            documentBytes = new byte[0];
        } else {
            try { documentBytes = crypto.decryptBytes(resume.getEncryptedRawContent(), resume.getRawContentNonce()); }
            catch (RuntimeException ex) { documentBytes = new byte[0]; }
        }
        documentBytes = normalizeDocumentBytes(reservation.sourceType(), documentBytes);
        List<EvidenceSpec> evidenceSpecs = evidenceSpecs(reservation.sourceType(), documentBytes);
        Set<UUID> evidenceIds = evidenceSpecs.stream().map(EvidenceSpec::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        MatchTask task = new MatchTask(UUID.randomUUID(), reservation.resumeId(), command.llmProfileId(), command.actorId(), reservation.resumeVersion(),
                command.jobFamily(), command.jobDescriptionText(), command.idempotencyKey(), callbackToken, evidenceIds, Instant.now());
        tasks.saveAndFlush(task);
        evidenceSpecs.forEach(e -> evidenceRepository.save(new AnalysisEvidence(e.id(), task.getId(), reservation.sourceType().name(), e.location(), e.start(), e.end(), e.excerpt())));
        task.markProcessing();
        tasks.saveAndFlush(task);
        if (profile != null && (documentBytes.length == 0 || evidenceSpecs.isEmpty())) { task.markFailed("MODEL_OUTPUT_INVALID"); tasks.saveAndFlush(task); return task; }
        if (profile != null) {
            var source = reservation.sourceType().name();
            var allowed = evidenceSpecs.stream().map(e -> new PythonAnalysisClient.AllowedEvidence(e.id(), e.location(), e.start(), e.end())).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var job = new PythonAnalysisClient.InternalAnalysisJob(task.getId(), task.getAttempt(), task.getResumeVersion(), source,
                    command.jobFamily(),
                    new PythonAnalysisClient.Document(Base64.getEncoder().encodeToString(documentBytes), "resume." + source.toLowerCase(Locale.ROOT)),
                    allowed, command.jobDescriptionText(), true, callbackUri(), callbackToken,
                    new PythonAnalysisClient.Provider(profile.baseUrl(), profile.model(), profile.apiKey()), UUID.randomUUID());
            try {
                if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                    org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() { public void afterCommit() { try { python.dispatch(job); } catch (RuntimeException ignored) { if (dispatchFailures != null) dispatchFailures.markFailed(task.getId(), "MODEL_UNAVAILABLE"); else { task.markFailed("MODEL_UNAVAILABLE"); tasks.save(task); } } } });
                } else python.dispatch(job);
            } catch (RuntimeException ex) { task.markFailed("MODEL_UNAVAILABLE"); tasks.saveAndFlush(task); }
        }
        // Flush evidence and the final task state before the transaction exits;
        // this keeps integrity failures inside the catchable operation boundary.
        tasks.saveAndFlush(task);
        return task;
    }

    private void validateCreateCommand(CreateMatchTaskCommand command) {
        if (command == null || command.actorId() == null || command.resumeId() == null || command.llmProfileId() == null
                || command.jobFamily() == null
                || command.jobDescriptionText() == null || command.jobDescriptionText().length() < 20 || command.jobDescriptionText().length() > 20_000
                || command.idempotencyKey() == null || command.idempotencyKey().length() < 16 || command.idempotencyKey().length() > 128) throw new IllegalArgumentException("VALIDATION_ERROR");
    }

    private MatchTask resolveTaskRace(CreateMatchTaskCommand command, DataIntegrityViolationException failure) {
        Optional<MatchTask> raced = inReadTransaction(() -> tasks.findByCreatorIdAndIdempotencyKey(command.actorId(), command.idempotencyKey()));
        if (raced.isEmpty()) throw failure;
        if (!sameSubmission(raced.get(), command)) throw new IdempotencyConflictException();
        return raced.get();
    }

    @Transactional(readOnly = true)
    public MatchTask getTask(UUID taskId, UUID actorId, UserRole role) {
        MatchTask task = tasks.findById(taskId).filter(t -> role == UserRole.ADMIN || t.getCreatorId().equals(actorId)).orElseThrow(ResourceNotFoundException::new);
        if (task.getState() == MatchTask.State.BLOCKED) throw new TaskGoneException();
        if (!lifecycle.isActiveAtVersion(task.getResumeId(), task.getResumeVersion())) throw new TaskGoneException();
        return task;
    }
    @Transactional(readOnly = true)
    public AnalysisResult getResult(UUID taskId, UUID actorId, UserRole role) {
        MatchTask task = getTask(taskId, actorId, role);
        if (!task.isResultAvailable()) throw new TaskNotReadyException();
        AnalysisResult result = results.findByTaskId(taskId).orElseThrow(TaskNotReadyException::new);
        return result;
    }
    List<AnalysisEvidence> evidenceForTask(UUID taskId) { return evidenceRepository.findByTaskId(taskId); }

    /**
     * Validate and persist a callback in an isolated transaction.  The receipt
     * primary key is the cross-instance gate; a racing insert is handled only
     * after its transaction has rolled back and the committed receipt is read
     * from a fresh transaction.
     */
    public synchronized CallbackResponse acceptCallback(AnalysisCallbackRequest request) {
        try {
            return inWriteTransaction(() -> acceptCallbackInTransaction(request));
        } catch (ReceiptRaceException race) {
            return resolveCallbackRace(request, race.getCause());
        } catch (RuntimeException failure) {
            DataIntegrityViolationException integrity = findDataIntegrityViolation(failure);
            if (integrity != null) return resolveCallbackRace(request, integrity);
            // Do not turn an unrelated failed transaction into a successful replay.
            throw failure;
        }
    }

    private CallbackResponse acceptCallbackInTransaction(AnalysisCallbackRequest request) {
        if (!validCallbackEnvelope(request)) {
            return CallbackResponse.error("VALIDATION_ERROR");
        }
        if (request.payloadHash() == null || !request.payloadHash().matches("[a-f0-9]{64}")) {
            return CallbackResponse.error("VALIDATION_ERROR");
        }
        final String computedPayloadHash = recomputePayloadHash(request);
        if (computedPayloadHash == null) return CallbackResponse.error("VALIDATION_ERROR");
        final boolean hashMatches = request.payloadHash().equals(computedPayloadHash);

        // Lock resume and task in the same order as soft-delete/archive.  The
        // first task read is deliberately unlocked and only supplies the
        // immutable resume identity; the task is reloaded with a write lock
        // before any state, token, or attempt decision is made.  Keeping one
        // lock order prevents a delete holding the resume row from deadlocking
        // with a late callback holding the task row.
        MatchTask taskSnapshot = tasks.findById(request.taskId()).orElseThrow(TaskGoneException::new);
        boolean resumeActive = lifecycle.lockActiveAtVersion(taskSnapshot.getResumeId(), taskSnapshot.getResumeVersion()).isPresent();
        MatchTask task = tasks.lockById(request.taskId()).orElseThrow(TaskGoneException::new);
        if (request.attempt() < task.getAttempt()) return CallbackResponse.error("STALE_ATTEMPT");
        if (request.attempt() != task.getAttempt()) return CallbackResponse.error("STALE_ATTEMPT");
        if (!task.tokenMatches(request.callbackToken()) || task.getState() == MatchTask.State.BLOCKED) {
            return CallbackResponse.error("TASK_GONE");
        }
        if (!resumeActive) {
            blockIfInFlight(task);
            return CallbackResponse.error("TASK_GONE");
        }

        var old = receipts.findByCallbackId(request.callbackId());
        if (old.isPresent()) {
            // A replay is valid only when both the claimed and recomputed hash
            // match the durable receipt.  Same hash with another task/token is
            // impossible to accept because the locked task checks above fail.
            return hashMatches && request.payloadHash().equals(old.get().payloadHash())
                    ? CallbackResponse.acceptedReplay() : CallbackResponse.error("IDEMPOTENCY_CONFLICT");
        }
        if (!hashMatches) return CallbackResponse.error("VALIDATION_ERROR");

        if (task.getState() == MatchTask.State.SUCCEEDED || task.getState() == MatchTask.State.FAILED || task.getState() == MatchTask.State.TIMED_OUT) {
            return CallbackResponse.error("TASK_GONE");
        }
        if (request.outcome() == null || !(request.outcome().equals("SUCCEEDED") || request.outcome().equals("FAILED") || request.outcome().equals("TIMED_OUT"))) return CallbackResponse.error("VALIDATION_ERROR");
        if (request.outcome().equals("SUCCEEDED") && request.result() == null) return CallbackResponse.error("MODEL_OUTPUT_INVALID");
        if (request.outcome().equals("SUCCEEDED") && request.errorCode() != null) return CallbackResponse.error("VALIDATION_ERROR");
        if (!request.outcome().equals("SUCCEEDED") && request.result() != null) return CallbackResponse.error("VALIDATION_ERROR");
        if (!request.outcome().equals("SUCCEEDED") && (request.errorCode() == null || request.errorCode().isBlank())) return CallbackResponse.error("VALIDATION_ERROR");
        if (request.errorCode() != null && !Set.of("MODEL_UNAVAILABLE","MODEL_OUTPUT_INVALID","MODEL_ENDPOINT_REJECTED","UNSUPPORTED_FILE").contains(request.errorCode())) return CallbackResponse.error("VALIDATION_ERROR");
        if ("SUCCEEDED".equals(request.outcome())) {
            try { validateResultSchema(request.result()); validateEvidence(task, request.result()); }
            catch (RuntimeException invalid) { return CallbackResponse.error("MODEL_OUTPUT_INVALID"); }
        }
        try {
            // saveAndFlush surfaces the unique-key race before result/task
            // updates, allowing the whole transaction to roll back cleanly.
            receipts.saveAndFlush(new CallbackReceipt(request.callbackId(), request.payloadHash(), Instant.now()));
        } catch (DataIntegrityViolationException duplicate) {
            throw new ReceiptRaceException(duplicate);
        }
        if ("SUCCEEDED".equals(request.outcome())) { results.save(AnalysisResult.from(task.getId(), task.getResumeId(), task.getResumeVersion(), task.getJobDescriptionText(), request)); task.markSucceeded(); }
        else if ("TIMED_OUT".equals(request.outcome())) task.markTimedOut(request.errorCode());
        else task.markFailed(request.errorCode());
        tasks.saveAndFlush(task);
        return CallbackResponse.ok();
    }

    private CallbackResponse resolveCallbackRace(AnalysisCallbackRequest request, Throwable failure) {
        // The failed write transaction may have used a stale persistence
        // context.  Re-read the receipt and re-run the same identity checks in
        // a fresh transaction before treating this as an idempotent replay.
        return inWriteTransaction(() -> resolveCallbackRaceInTransaction(request, failure));
    }

    private CallbackResponse resolveCallbackRaceInTransaction(AnalysisCallbackRequest request, Throwable failure) {
        if (!validCallbackEnvelope(request) || request.payloadHash() == null
                || !request.payloadHash().matches("[a-f0-9]{64}")) {
            return CallbackResponse.error("VALIDATION_ERROR");
        }
        String computedPayloadHash = recomputePayloadHash(request);
        if (computedPayloadHash == null || !request.payloadHash().equals(computedPayloadHash)) {
            // The callback ID was already claimed by another transaction, so
            // a changed body is an idempotency conflict rather than a replay.
            return CallbackResponse.error("IDEMPOTENCY_CONFLICT");
        }

        Optional<CallbackReceipt> raced = receipts.findByCallbackId(request.callbackId());
        if (raced.isEmpty()) {
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("callback receipt persistence failed", failure);
        }

        MatchTask taskSnapshot = tasks.findById(request.taskId()).orElseThrow(TaskGoneException::new);
        boolean resumeActive = lifecycle.lockActiveAtVersion(taskSnapshot.getResumeId(), taskSnapshot.getResumeVersion()).isPresent();
        MatchTask task = tasks.lockById(request.taskId()).orElseThrow(TaskGoneException::new);
        if (request.attempt() < task.getAttempt()) return CallbackResponse.error("STALE_ATTEMPT");
        if (request.attempt() != task.getAttempt()) return CallbackResponse.error("STALE_ATTEMPT");
        if (!task.tokenMatches(request.callbackToken()) || task.getState() == MatchTask.State.BLOCKED) {
            return CallbackResponse.error("TASK_GONE");
        }
        if (!resumeActive) {
            blockIfInFlight(task);
            return CallbackResponse.error("TASK_GONE");
        }
        return request.payloadHash().equals(raced.get().payloadHash())
                ? CallbackResponse.acceptedReplay() : CallbackResponse.error("IDEMPOTENCY_CONFLICT");
    }

    private void blockIfInFlight(MatchTask task) {
        if (task.getState() == MatchTask.State.QUEUED || task.getState() == MatchTask.State.PROCESSING) {
            task.markBlocked();
            tasks.saveAndFlush(task);
        }
    }

    private static boolean validCallbackEnvelope(AnalysisCallbackRequest request) {
        return request != null && request.callbackId() != null && request.taskId() != null
                && request.callbackToken() != null && request.callbackToken().length() >= 32 && request.callbackToken().length() <= 1024
                && request.correlationId() != null && request.attempt() >= 1;
    }

    private static String recomputePayloadHash(AnalysisCallbackRequest request) {
        try { return CallbackPayloadHash.compute(request); }
        catch (RuntimeException invalidHashInput) { return null; }
    }

    private void validateEvidence(MatchTask task, AnalysisCallbackRequest.AnalysisResultPayload result) {
        if (result == null || result.score() == null || result.requirements() == null || result.suggestions() == null) throw new EvidenceReferenceException();
        String sourceText = null;
        for (var requirement : result.requirements()) {
            if (requirement == null || requirement.evidence() == null) throw new EvidenceReferenceException();
            for (var evidence : requirement.evidence()) {
                if (sourceText == null) sourceText = sourceTextForTask(task);
                validateEvidence(task, evidence, sourceText);
            }
        }
        for (var suggestion : result.suggestions()) for (var id : suggestion.evidenceIds())
            if (evidenceRepository.findByTaskId(task.getId()).stream().noneMatch(e -> e.getId().equals(id))) throw new EvidenceReferenceException();
    }
    private void validateEvidence(MatchTask task, AnalysisCallbackRequest.EvidenceReference evidence, String sourceText) {
        var allowed = evidence == null ? null : evidenceRepository.findByTaskId(task.getId()).stream().filter(e -> e.getId().equals(evidence.evidenceId())).findFirst().orElse(null);
        int sourceLength = sourceText == null ? -1 : sourceText.codePointCount(0, sourceText.length());
        if (evidence == null || allowed == null || allowed.getSourceStart() < 0 || allowed.getSourceEnd() <= allowed.getSourceStart() || allowed.getSourceEnd() > sourceLength || evidence.sourceStart() < allowed.getSourceStart() || evidence.sourceEnd() <= evidence.sourceStart() || evidence.sourceEnd() > allowed.getSourceEnd() || evidence.excerpt() == null || evidence.excerpt().isBlank() || evidence.excerpt().length() > 5000 || !Double.isFinite(evidence.confidence()) || evidence.confidence() < 0 || evidence.confidence() > 1) throw new EvidenceReferenceException();
        String redactedExcerpt;
        try { redactedExcerpt = ResumeTextRedactor.redactedSlice(sourceText, evidence.sourceStart(), evidence.sourceEnd()); }
        catch (RuntimeException invalidRange) { throw new EvidenceReferenceException(); }
        // Python always returns the redacted representation.  Requiring that
        // exact value keeps PII out of the durable result and public response.
        if (!evidence.excerpt().equals(redactedExcerpt)) throw new EvidenceReferenceException();
    }
    /** Decrypt the durable resume only inside the callback transaction. */
    private String sourceTextForTask(MatchTask task) {
        if (crypto == null) throw new EvidenceReferenceException();
        Resume resume = lifecycle.findActiveForAnalysis(task.getResumeId(), task.getResumeVersion())
                .orElseThrow(EvidenceReferenceException::new);
        if (resume.getEncryptedRawContent() == null || resume.getRawContentNonce() == null) throw new EvidenceReferenceException();
        final byte[] bytes;
        try { bytes = normalizeDocumentBytes(resume.getSourceType(), crypto.decryptBytes(resume.getEncryptedRawContent(), resume.getRawContentNonce())); }
        catch (RuntimeException invalidCiphertext) { throw new EvidenceReferenceException(); }
        if (bytes.length == 0) throw new EvidenceReferenceException();
        if (resume.getSourceType() == Resume.SourceType.TXT) return new String(bytes, StandardCharsets.UTF_8);
        return String.join("\n", docxParagraphs(bytes));
    }
    private static void validateResultSchema(AnalysisCallbackRequest.AnalysisResultPayload result) {
        if (!finiteBetween(result.score().skills()) || !finiteBetween(result.score().projectExperience()) || !finiteBetween(result.score().workContent()) || !finiteBetween(result.score().educationExperience()) || !finiteBetween(result.score().softSkills()) || !finiteBetween(result.score().composite())) throw new EvidenceReferenceException();
        java.math.BigDecimal expectedDecimal = java.math.BigDecimal.valueOf(result.score().skills()).multiply(java.math.BigDecimal.valueOf(.40))
                .add(java.math.BigDecimal.valueOf(result.score().projectExperience()).multiply(java.math.BigDecimal.valueOf(.25)))
                .add(java.math.BigDecimal.valueOf(result.score().workContent()).multiply(java.math.BigDecimal.valueOf(.15)))
                .add(java.math.BigDecimal.valueOf(result.score().educationExperience()).multiply(java.math.BigDecimal.valueOf(.10)))
                .add(java.math.BigDecimal.valueOf(result.score().softSkills()).multiply(java.math.BigDecimal.valueOf(.10)))
                .setScale(4, java.math.RoundingMode.HALF_UP);
        double expected = expectedDecimal.doubleValue();
        if (Double.compare(expected, result.score().composite()) != 0) throw new EvidenceReferenceException();
        Set<UUID> requirementIds = new HashSet<>();
        for (var requirement : result.requirements()) {
            if (requirement == null || requirement.requirementId() == null || !safeText(requirement.jobRequirementText(), 20_000) || !Set.of("MANDATORY", "PREFERRED").contains(requirement.requirementType()) || !Set.of("SATISFIED", "PARTIALLY_SATISFIED", "RELATED_BUT_EVIDENCE_INSUFFICIENT", "UNMET").contains(requirement.matchStatus()) || !Set.of("EXACT", "SEMANTIC", "RELATED", "NO_MATCH").contains(requirement.matchType()) || !Set.of("SKILLS", "PROJECT_EXPERIENCE", "WORK_CONTENT", "EDUCATION_EXPERIENCE", "SOFT_SKILLS").contains(requirement.component()) || requirement.evidence() == null || !Set.of("NONE", "LOW", "MEDIUM", "HIGH").contains(requirement.evidenceStrength()) || !Set.of("SUPPORTED_FACT", "WORDING_ONLY_REWRITE", "NEEDS_USER_CONFIRMATION", "RISKY_OR_UNSUPPORTED").contains(requirement.suggestionState()) || !finiteBetween(requirement.componentScore()) || (requirement.gap() != null && (requirement.gap().length() > 5_000 || !ResumeTextRedactor.isRedacted(requirement.gap())))) throw new EvidenceReferenceException();
            if (("SATISFIED".equals(requirement.matchStatus()) || "PARTIALLY_SATISFIED".equals(requirement.matchStatus())) && requirement.evidence().isEmpty()) throw new EvidenceReferenceException();
            if (("SUPPORTED_FACT".equals(requirement.suggestionState()) || "WORDING_ONLY_REWRITE".equals(requirement.suggestionState())) && requirement.evidence().isEmpty()) throw new EvidenceReferenceException();
            if (!requirementIds.add(requirement.requirementId())) throw new EvidenceReferenceException();
        }
        for (var suggestion : result.suggestions()) if (suggestion == null || suggestion.suggestionId() == null || suggestion.requirementId() == null || !requirementIds.contains(suggestion.requirementId()) || !Set.of("SUPPORTED_FACT", "WORDING_ONLY_REWRITE", "NEEDS_USER_CONFIRMATION", "RISKY_OR_UNSUPPORTED").contains(suggestion.state()) || !safeText(suggestion.proposedText(), 5_000) || suggestion.evidenceIds() == null || (("SUPPORTED_FACT".equals(suggestion.state()) || "WORDING_ONLY_REWRITE".equals(suggestion.state())) && suggestion.evidenceIds().isEmpty())) throw new EvidenceReferenceException();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean safeText(String value, int maxLength) { return value != null && !value.isBlank() && value.length() <= maxLength && ResumeTextRedactor.isRedacted(value); }
    private static boolean finiteBetween(double value) { return Double.isFinite(value) && value >= 0 && value <= 1; }
    private static String randomToken() { byte[] bytes = new byte[48]; new java.security.SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static URI callbackUri() { return URI.create(System.getProperty("matching.callback-url", System.getProperty("app.matching-callback-url", System.getenv().getOrDefault("MATCHING_CALLBACK_URL", "http://127.0.0.1:8080/internal/v1/analysis-results")))); }
    private static byte[] normalizeDocumentBytes(Resume.SourceType type, byte[] bytes) {
        if (bytes == null) return new byte[0];
        if (type != Resume.SourceType.TXT) return bytes;
        String normalized = new String(bytes, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        return normalized.getBytes(StandardCharsets.UTF_8);
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

    private static DataIntegrityViolationException findDataIntegrityViolation(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current instanceof DataIntegrityViolationException violation) return violation;
        }
        return null;
    }

    private static final class ReceiptRaceException extends RuntimeException {
        ReceiptRaceException(DataIntegrityViolationException cause) { super(cause); }
    }

    private record EvidenceSpec(UUID id, String location, int start, int end, String excerpt) {}
    private static List<EvidenceSpec> evidenceSpecs(Resume.SourceType type, byte[] bytes) {
        String text; List<EvidenceSpec> out = new ArrayList<>();
        if (type == Resume.SourceType.TXT) {
            text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
            int offset=0; String[] lines=text.split("\\n", -1);
            for (int i=0;i<lines.length;i++) {
                int end=offset+lines[i].codePointCount(0, lines[i].length());
                // Python rejects empty ranges; retain offsets across blank
                // lines but only expose non-empty evidence slices.
                if (end > offset) out.add(new EvidenceSpec(UUID.randomUUID(),"txt:"+i,offset,end,ResumeTextRedactor.redactedSlice(text, offset, end)));
                offset=end+1;
            }
        } else {
            List<String> paragraphs = docxParagraphs(bytes); text = String.join("\n", paragraphs); int offset=0;
            for(int i=0;i<paragraphs.size();i++) {
                int end=offset+paragraphs.get(i).codePointCount(0, paragraphs.get(i).length());
                if (end > offset) out.add(new EvidenceSpec(UUID.randomUUID(),"paragraph:"+i,offset,end,ResumeTextRedactor.redactedSlice(text, offset, end)));
                offset=end+1;
            }
        }
        return out;
    }
    private static boolean sameSubmission(MatchTask task, CreateMatchTaskCommand command) { return task.getResumeId().equals(command.resumeId()) && task.getLlmProfileId().equals(command.llmProfileId()) && task.getJobFamily() == command.jobFamily() && task.getJobDescriptionText().equals(command.jobDescriptionText()); }
    private static List<String> docxParagraphs(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return List.of();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            int entryCount = 0;
            long totalUncompressed = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entryCount > MAX_DOCX_ENTRY_COUNT) throw new DocxLimitException();
                long declaredCompressed = entry.getCompressedSize();
                if (declaredCompressed >= 0 && declaredCompressed > MAX_DOCX_ENTRY_COMPRESSED_BYTES) {
                    throw new DocxLimitException();
                }
                boolean documentXml = "word/document.xml".equals(entry.getName());
                long entryLimit = documentXml ? MAX_DOCX_XML_BYTES : MAX_DOCX_ENTRY_UNCOMPRESSED_BYTES;
                long declaredUncompressed = entry.getSize();
                if (declaredUncompressed >= 0 && declaredUncompressed > entryLimit) {
                    throw new DocxLimitException();
                }
                if (declaredUncompressed >= 0 && totalUncompressed > MAX_DOCX_TOTAL_UNCOMPRESSED_BYTES - declaredUncompressed) {
                    throw new DocxLimitException();
                }
                EntryBytes content = readEntryBounded(zip, entryLimit, totalUncompressed, documentXml);
                totalUncompressed = content.totalUncompressed();
                if (!documentXml) continue;

                byte[] xml = content.bytes();
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                f.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                f.setFeature("http://xml.org/sax/features/external-general-entities", false);
                f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                f.setXIncludeAware(false);
                f.setExpandEntityReferences(false);
                Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
                List<String> out = new ArrayList<>();
                NodeList ps = doc.getElementsByTagNameNS("*", "p");
                for (int i = 0; i < ps.getLength(); i++) {
                    StringBuilder s = new StringBuilder();
                    appendDocxText(ps.item(i), s);
                    out.add(s.toString());
                }
                return out;
            }
        } catch (Exception ignored) {}
        return List.of();
    }

    private static EntryBytes readEntryBounded(ZipInputStream zip, long entryLimit,
                                                long totalBefore, boolean collect) throws IOException {
        long entryBytes = 0;
        long total = totalBefore;
        ByteArrayOutputStream output = collect ? new ByteArrayOutputStream((int) Math.min(entryLimit, 8192)) : null;
        byte[] buffer = new byte[DOCX_READ_BUFFER_BYTES];
        int read;
        while ((read = zip.read(buffer)) != -1) {
            entryBytes += read;
            total += read;
            if (entryBytes > entryLimit || total > MAX_DOCX_TOTAL_UNCOMPRESSED_BYTES) {
                throw new DocxLimitException();
            }
            if (collect) output.write(buffer, 0, read);
        }
        return new EntryBytes(collect ? output.toByteArray() : null, total);
    }

    private record EntryBytes(byte[] bytes, long totalUncompressed) {}
    private static final class DocxLimitException extends IOException {}
    private static void appendDocxText(Node node, StringBuilder out) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            String name = node.getLocalName();
            if ("t".equals(name) || "delText".equals(name)) { out.append(node.getTextContent()); return; }
            if ("tab".equals(name)) { out.append('\t'); return; }
            if ("br".equals(name) || "cr".equals(name)) { out.append('\n'); return; }
        }
        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) appendDocxText(children.item(i), out);
    }
    public record CallbackResponse(String code, boolean accepted) { static CallbackResponse ok(){return new CallbackResponse("ACCEPTED",true);} static CallbackResponse acceptedReplay(){return new CallbackResponse("ACCEPTED_REPLAY",true);} static CallbackResponse error(String c){return new CallbackResponse(c,false);} }
}
