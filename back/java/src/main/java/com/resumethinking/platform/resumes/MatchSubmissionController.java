package com.resumethinking.platform.resumes;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.resumethinking.platform.auth.UserRole;
import com.resumethinking.platform.matching.JobFamily;
import com.resumethinking.platform.matching.MatchTask;
import com.resumethinking.platform.ids.BusinessIdType;
import com.resumethinking.platform.ids.ReadableIdGenerator;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v3")
public class MatchSubmissionController {
    private final MatchSubmissionService service;

    public MatchSubmissionController(MatchSubmissionService service) {
        this.service = service;
    }

    @PostMapping(value = "/match-submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MatchTaskResponse> initial(
            @RequestAttribute("actorId") String actorId,
            @RequestAttribute("role") UserRole role,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam String llmProfileId,
            @RequestParam JobFamily jobFamily,
            @RequestParam String jobDescriptionText,
            @RequestParam String idempotencyKey) {
        validateId(BusinessIdType.PROFILE, llmProfileId);
        validateExplicitTitle(title);
        String filename = validatedFilename(file);
        MatchTask task = service.submitInitial(bytes(file), filename, title,
                command(actorId, role, llmProfileId, jobFamily, jobDescriptionText, idempotencyKey));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(MatchTaskResponse.from(task));
    }

    @PostMapping(value = "/resumes/{resumeId}/match-submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<MatchTaskResponse> rematch(
            @RequestAttribute("actorId") String actorId,
            @RequestAttribute("role") UserRole role,
            @PathVariable String resumeId,
            @RequestParam String expectedEffectiveRevisionId,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(required = false) String title,
            @RequestParam String llmProfileId,
            @RequestParam JobFamily jobFamily,
            @RequestParam String jobDescriptionText,
            @RequestParam String idempotencyKey) {
        validateId(BusinessIdType.RESUME, resumeId);
        validateId(BusinessIdType.REVISION, expectedEffectiveRevisionId);
        validateId(BusinessIdType.PROFILE, llmProfileId);
        validateExplicitTitle(title);
        String filename = file == null ? null : validatedFilename(file);
        MatchTask task = service.rematch(resumeId, expectedEffectiveRevisionId,
                file == null ? null : bytes(file), filename, title,
                command(actorId, role, llmProfileId, jobFamily, jobDescriptionText, idempotencyKey));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(MatchTaskResponse.from(task));
    }

    @GetMapping("/resumes")
    public ResumePage list(@RequestAttribute("actorId") String actorId,
                           @RequestAttribute("role") UserRole role,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int pageSize) {
        validatePage(page, pageSize);
        var values = service.listEffective(actorId, role,
                PageRequest.of(page - 1, pageSize, Sort.by("id")));
        return new ResumePage(values.getContent().stream().map(ResumeResponse::from).toList(),
                page, pageSize, values.getTotalElements(), values.getTotalPages());
    }

    @GetMapping("/resumes/{resumeId}/match-context")
    public ResumeMatchContext context(@RequestAttribute("actorId") String actorId,
                                      @RequestAttribute("role") UserRole role,
                                      @PathVariable String resumeId) {
        return service.getMatchContext(resumeId, actorId, role);
    }

    @DeleteMapping("/resumes/{resumeId}")
    public ResponseEntity<Void> delete(@RequestAttribute("actorId") String actorId,
                                       @RequestAttribute("role") UserRole role,
                                       @PathVariable String resumeId,
                                       @Valid @RequestBody DeleteRequest request) {
        service.softDelete(new DeleteResumeCommand(resumeId, actorId, role,
                request.confirmationText(), request.expectedVersion()));
        return ResponseEntity.noContent().build();
    }

    private static MatchSubmissionService.SubmissionCommand command(
            String actorId, UserRole role, String profileId, JobFamily family, String jobText, String key) {
        return new MatchSubmissionService.SubmissionCommand(actorId, role, profileId, family, jobText, key);
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException unreadable) {
            throw new IllegalArgumentException("VALIDATION_ERROR");
        }
    }

    private static String validatedFilename(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("VALIDATION_ERROR");
        if (file.getSize() > 5_000_000) throw new IllegalArgumentException("PAYLOAD_TOO_LARGE");
        String filename = file.getOriginalFilename() == null ? "resume.txt" : file.getOriginalFilename();
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith(".txt") && !lower.endsWith(".docx")) {
            throw new IllegalArgumentException("UNSUPPORTED_FILE");
        }
        return filename;
    }

    private static void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("VALIDATION_ERROR");
    }

    private static void validateId(BusinessIdType type, String value) {
        if (!ReadableIdGenerator.isValid(type, value)) throw new IllegalArgumentException("VALIDATION_ERROR");
    }

    private static void validateExplicitTitle(String title) {
        if (title != null && (title.isBlank() || title.trim().length() > 200)) {
            throw new IllegalArgumentException("VALIDATION_ERROR");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DeleteRequest(@NotBlank String confirmationText, @NotNull @Min(0) Long expectedVersion) {}

    public record MatchTaskResponse(String id, String resumeId, String revisionId, String llmProfileId,
                                    JobFamily jobFamily, MatchTask.State state,
                                    MatchTask.PublicationState publicationState, int attempt, long resumeVersion,
                                    String failureCode, boolean resultAvailable, Instant createdAt, Instant updatedAt) {
        static MatchTaskResponse from(MatchTask task) {
            return new MatchTaskResponse(task.getId(), task.getResumeId(), task.getRevisionId(),
                    task.getLlmProfileId(), task.getJobFamily(), task.getState(), task.getPublicationState(),
                    task.getAttempt(), task.getResumeVersion(), task.getFailureCode(), task.isResultAvailable(),
                    task.getCreatedAt(), task.getUpdatedAt());
        }
    }

    public record ResumeResponse(String id, String ownerId, String title, Resume.SourceType sourceType, int status,
                                 long version, String effectiveRevisionId, String pendingRevisionId,
                                 String latestSuccessfulTaskId, Instant createdAt, Instant updatedAt) {
        static ResumeResponse from(MatchSubmissionService.EffectiveResume resume) {
            Resume value = resume.resume();
            return new ResumeResponse(value.getId(), value.getOwnerId(), value.getTitle(), value.getSourceType(),
                    value.getStatus(), value.getVersion(), value.getEffectiveRevisionId(), value.getPendingRevisionId(),
                    resume.latestSuccessfulTaskId(), value.getCreatedAt(), value.getUpdatedAt());
        }
    }

    public record ResumePage(List<ResumeResponse> items, int page, int pageSize, long totalItems, int totalPages) {}
}
