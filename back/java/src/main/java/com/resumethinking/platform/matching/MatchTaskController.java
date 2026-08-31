package com.resumethinking.platform.matching;

import com.resumethinking.platform.auth.UserRole;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController @RequestMapping("/api/v1/match-tasks")
public class MatchTaskController {
    private final MatchTaskService service;
    public MatchTaskController(MatchTaskService service) { this.service = service; }
    @PostMapping public ResponseEntity<MatchTaskResponse> create(@RequestAttribute("actorId") String actor, @RequestAttribute("role") UserRole role, @Valid @RequestBody CreateMatchTaskRequest body) {
        var task = service.createTask(new CreateMatchTaskCommand(actor, body.resumeId(), body.llmProfileId(), body.jobFamily(), body.jobDescriptionText(), body.idempotencyKey(), role));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(MatchTaskResponse.from(task));
    }
    @GetMapping("/{taskId}") public MatchTaskResponse get(@RequestAttribute("actorId") String actor, @RequestAttribute("role") UserRole role, @PathVariable String taskId) { return MatchTaskResponse.from(service.getTask(taskId, actor, role)); }
    @GetMapping("/{taskId}/result") public MatchResultResponse result(@RequestAttribute("actorId") String actor, @RequestAttribute("role") UserRole role, @PathVariable String taskId) { return MatchResultResponse.from(service.getResult(taskId, actor, role), service.evidenceForTask(taskId)); }
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateMatchTaskRequest(
            @NotNull String resumeId,
            @NotNull String llmProfileId,
            @NotNull JobFamily jobFamily,
            @NotBlank @Size(min = 20, max = 20000) String jobDescriptionText,
            @NotBlank @Size(min = 16, max = 128) String idempotencyKey) {}
    public record MatchTaskResponse(String id, String resumeId, String llmProfileId, JobFamily jobFamily, MatchTask.State state, int attempt, long resumeVersion, String failureCode, boolean resultAvailable, java.time.Instant createdAt, java.time.Instant updatedAt) { static MatchTaskResponse from(MatchTask t){return new MatchTaskResponse(t.getId(),t.getResumeId(),t.getLlmProfileId(),t.getJobFamily(),t.getState(),t.getAttempt(),t.getResumeVersion(),t.getFailureCode(),t.isResultAvailable(),t.getCreatedAt(),t.getUpdatedAt());} }
}
