package com.resumethinking.platform.matching;

import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.crypto.AesGcmCryptoService;
import com.resumethinking.platform.profiles.*;
import com.resumethinking.platform.resumes.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ResumeLifecycleService lifecycle;
    private final LlmProfileService profiles;
    private final MatchTaskRepository tasks;
    private final PythonAnalysisClient python;
    private final AnalysisResultRepository results;
    private final CallbackReceiptRepository receipts;
    private final AnalysisEvidenceRepository evidenceRepository;
    private final AesGcmCryptoService crypto;
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python) {
        this(lifecycle, profiles, tasks, python, new AnalysisResultRepository.InMemory(), new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), null);
    }
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python, AnalysisResultRepository results) {
        this(lifecycle, profiles, tasks, python, results, new CallbackReceiptRepository.InMemory(), new AnalysisEvidenceRepository.InMemory(), null);
    }
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python, AnalysisResultRepository results, CallbackReceiptRepository receipts) {
        this(lifecycle, profiles, tasks, python, results, receipts, new AnalysisEvidenceRepository.InMemory(), null);
    }
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python, AnalysisResultRepository results, CallbackReceiptRepository receipts, AnalysisEvidenceRepository evidenceRepository) {
        this(lifecycle, profiles, tasks, python, results, receipts, evidenceRepository, null);
    }
    @Autowired
    public MatchTaskService(ResumeLifecycleService lifecycle, LlmProfileService profiles, MatchTaskRepository tasks, PythonAnalysisClient python, AnalysisResultRepository results, CallbackReceiptRepository receipts, AnalysisEvidenceRepository evidenceRepository, AesGcmCryptoService crypto) {
        this.lifecycle = lifecycle; this.profiles = profiles; this.tasks = tasks; this.python = python; this.results = results; this.receipts = receipts; this.evidenceRepository = evidenceRepository; this.crypto = crypto;
    }

    @Transactional
    public synchronized MatchTask createTask(CreateMatchTaskCommand command) {
        if (command == null || command.actorId() == null || command.resumeId() == null || command.llmProfileId() == null
                || command.jobDescriptionText() == null || command.jobDescriptionText().length() < 20
                || command.idempotencyKey() == null || command.idempotencyKey().length() < 16) throw new IllegalArgumentException("VALIDATION_ERROR");
        var existing = tasks.findByCreatorIdAndIdempotencyKey(command.actorId(), command.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().getResumeId().equals(command.resumeId()) || !existing.get().getLlmProfileId().equals(command.llmProfileId())
                    || !existing.get().getJobDescriptionText().equals(command.jobDescriptionText())) throw new IdempotencyConflictException();
            return existing.get();
        }
        ResumeAnalysisReservation reservation = lifecycle.reserveForAnalysis(command.resumeId(), command.actorId(), command.role() == null ? UserRole.USER : command.role());
        DispatchLlmProfile profile = profiles == null ? null : profiles.decryptForDispatch(command.actorId(), command.llmProfileId());
        String callbackToken = randomToken();
        Resume resume = lifecycle.findActiveForAnalysis(reservation.resumeId(), reservation.resumeVersion()).orElseThrow(ResourceNotFoundException::new);
        byte[] documentBytes;
        if (resume.getRawContentNonce() == null || crypto == null) {
            documentBytes = resume.getRawContentNonce() == null ? new byte[0] : resume.getEncryptedRawContent();
        } else {
            try { documentBytes = crypto.decryptBytes(resume.getEncryptedRawContent(), resume.getRawContentNonce()); }
            catch (RuntimeException ex) { documentBytes = new byte[0]; }
        }
        List<EvidenceSpec> evidenceSpecs = evidenceSpecs(reservation.sourceType(), documentBytes);
        Set<UUID> evidenceIds = evidenceSpecs.stream().map(EvidenceSpec::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        MatchTask task = new MatchTask(UUID.randomUUID(), reservation.resumeId(), command.llmProfileId(), command.actorId(), reservation.resumeVersion(),
                command.jobDescriptionText(), command.idempotencyKey(), callbackToken, evidenceIds, Instant.now());
        tasks.save(task);
        evidenceSpecs.forEach(e -> evidenceRepository.save(new AnalysisEvidence(e.id(), task.getId(), e.location(), e.start(), e.end())));
        if (documentBytes.length == 0 && resume.getRawContentNonce() == null) { task.markFailed("MODEL_OUTPUT_INVALID"); tasks.save(task); return task; }
        if (profile != null) {
            var source = reservation.sourceType().name();
            var allowed = evidenceSpecs.stream().map(e -> new PythonAnalysisClient.AllowedEvidence(e.id(), e.location(), e.start(), e.end())).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            var job = new PythonAnalysisClient.InternalAnalysisJob(task.getId(), task.getAttempt(), task.getResumeVersion(), source,
                    new PythonAnalysisClient.Document(Base64.getEncoder().encodeToString(documentBytes), "resume." + source.toLowerCase(Locale.ROOT)),
                    allowed, command.jobDescriptionText(), true, URI.create(System.getProperty("matching.callback-url", System.getenv().getOrDefault("MATCHING_CALLBACK_URL", "http://127.0.0.1:8080/internal/v1/analysis-results"))), callbackToken,
                    new PythonAnalysisClient.Provider(profile.baseUrl(), profile.model(), profile.apiKey()), UUID.randomUUID());
            python.dispatch(job);
        }
        return task;
    }

    @Transactional(readOnly = true)
    public MatchTask getTask(UUID taskId, UUID actorId, UserRole role) {
        MatchTask task = tasks.findById(taskId).filter(t -> role == UserRole.ADMIN || t.getCreatorId().equals(actorId)).orElseThrow(ResourceNotFoundException::new);
        if (task.getState() == MatchTask.State.BLOCKED) throw new TaskGoneException();
        return task;
    }
    @Transactional(readOnly = true)
    public AnalysisResult getResult(UUID taskId, UUID actorId, UserRole role) {
        MatchTask task = getTask(taskId, actorId, role);
        if (!task.isResultAvailable()) throw new TaskNotReadyException();
        return results.findByTaskId(taskId).orElseThrow(TaskNotReadyException::new);
    }

    @Transactional
    public CallbackResponse acceptCallback(AnalysisCallbackRequest request) {
        if (request == null || request.callbackId() == null) return CallbackResponse.error("VALIDATION_ERROR");
        var old = receipts.findByCallbackId(request.callbackId());
        if (old.isPresent()) return old.get().payloadHash().equals(request.payloadHash()) ? CallbackResponse.acceptedReplay() : CallbackResponse.error("IDEMPOTENCY_CONFLICT");
        MatchTask task = tasks.lockById(request.taskId()).orElseThrow(TaskGoneException::new);
        if (request.attempt() < task.getAttempt()) return CallbackResponse.error("STALE_ATTEMPT");
        if (!lifecycle.isActiveAtVersion(task.getResumeId(), task.getResumeVersion())) {
            task.markBlocked(); tasks.save(task); return CallbackResponse.error("TASK_GONE");
        }
        if (request.attempt() != task.getAttempt()) return CallbackResponse.error("STALE_ATTEMPT");
        if (!task.tokenMatches(request.callbackToken())) return CallbackResponse.error("TASK_GONE");
        if (request.outcome() == null || !(request.outcome().equals("SUCCEEDED") || request.outcome().equals("FAILED") || request.outcome().equals("TIMED_OUT"))) return CallbackResponse.error("VALIDATION_ERROR");
        if (request.outcome().equals("SUCCEEDED") && request.result() == null) return CallbackResponse.error("MODEL_OUTPUT_INVALID");
        if (!request.outcome().equals("SUCCEEDED") && (request.errorCode() == null || request.errorCode().isBlank())) return CallbackResponse.error("VALIDATION_ERROR");
        if (request.payloadHash() == null || !request.payloadHash().matches("[a-f0-9]{64}")) return CallbackResponse.error("VALIDATION_ERROR");
        if (!request.payloadHash().equals(CallbackPayloadHash.compute(request))) return CallbackResponse.error("VALIDATION_ERROR");
        if ("SUCCEEDED".equals(request.outcome())) validateEvidence(task, request.result());
        receipts.save(new CallbackReceipt(request.callbackId(), request.payloadHash(), Instant.now()));
        if ("SUCCEEDED".equals(request.outcome())) { results.save(AnalysisResult.from(task.getId(), task.getResumeId(), task.getResumeVersion(), task.getJobDescriptionText(), request)); task.markSucceeded(); }
        else if ("TIMED_OUT".equals(request.outcome())) task.markTimedOut(request.errorCode());
        else task.markFailed(request.errorCode());
        tasks.save(task);
        return CallbackResponse.ok();
    }

    private void validateEvidence(MatchTask task, AnalysisCallbackRequest.AnalysisResultPayload result) {
        if (result == null) throw new EvidenceReferenceException();
        for (var requirement : result.requirements()) for (var evidence : requirement.evidence()) validateEvidence(task, evidence);
        for (var suggestion : result.suggestions()) for (var id : suggestion.evidenceIds()) if (!task.evidenceAllowed(id)) throw new EvidenceReferenceException();
    }
    private void validateEvidence(MatchTask task, AnalysisCallbackRequest.EvidenceReference evidence) {
        var allowed = evidenceRepository.findByTaskId(task.getId()).stream().filter(e -> e.getId().equals(evidence.evidenceId())).findFirst().orElse(null);
        if (!task.evidenceAllowed(evidence.evidenceId()) || allowed == null || evidence.sourceStart() < allowed.getSourceStart() || evidence.sourceEnd() < evidence.sourceStart() || evidence.sourceEnd() > allowed.getSourceEnd() || evidence.excerpt() == null || evidence.excerpt().isBlank()) throw new EvidenceReferenceException();
    }
    private static String randomToken() { byte[] bytes = new byte[48]; new java.security.SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private record EvidenceSpec(UUID id, String location, int start, int end) {}
    private static List<EvidenceSpec> evidenceSpecs(Resume.SourceType type, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8); List<EvidenceSpec> out = new ArrayList<>();
        if (type == Resume.SourceType.TXT) { int offset=0; String[] lines=text.split("\\R", -1); for (int i=0;i<lines.length;i++){int end=offset+lines[i].length(); out.add(new EvidenceSpec(UUID.randomUUID(),"txt:"+i,offset,end)); offset=end+1;} }
        else { List<String> paragraphs = docxParagraphs(bytes); int offset=0; for(int i=0;i<paragraphs.size();i++){int end=offset+paragraphs.get(i).length(); out.add(new EvidenceSpec(UUID.randomUUID(),"paragraph:"+i,offset,end)); offset=end+1;} }
        if (out.isEmpty()) out.add(new EvidenceSpec(UUID.randomUUID(), type == Resume.SourceType.TXT ? "txt:0" : "paragraph:0", 0, 0)); return out;
    }
    private static List<String> docxParagraphs(byte[] bytes) {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; while ((entry = zip.getNextEntry()) != null) if ("word/document.xml".equals(entry.getName())) {
                byte[] xml = zip.readAllBytes(); DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); f.setFeature("http://xml.org/sax/features/external-general-entities", false); f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); f.setXIncludeAware(false); f.setExpandEntityReferences(false);
                Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml)); List<String> out = new ArrayList<>(); NodeList ps = doc.getElementsByTagNameNS("*", "p"); for (int i=0;i<ps.getLength();i++){StringBuilder s=new StringBuilder(); NodeList ts=((Element)ps.item(i)).getElementsByTagNameNS("*", "t"); for(int j=0;j<ts.getLength();j++) s.append(ts.item(j).getTextContent()); out.add(s.toString());} return out;
            }
        } catch (Exception ignored) {}
        return List.of();
    }
    public record CallbackResponse(String code, boolean accepted) { static CallbackResponse ok(){return new CallbackResponse("ACCEPTED",true);} static CallbackResponse acceptedReplay(){return new CallbackResponse("ACCEPTED_REPLAY",true);} static CallbackResponse error(String c){return new CallbackResponse(c,false);} }
}
