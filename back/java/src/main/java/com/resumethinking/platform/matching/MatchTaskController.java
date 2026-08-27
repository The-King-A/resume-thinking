package com.resumethinking.platform.matching;

import com.resumethinking.platform.auth.UserRole;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController @RequestMapping("/api/v1/match-tasks")
public class MatchTaskController {
    private final MatchTaskService service;
    public MatchTaskController(MatchTaskService service) { this.service = service; }
    @PostMapping public ResponseEntity<MatchTaskResponse> create(@RequestAttribute("actorId") UUID actor, @RequestAttribute("role") UserRole role, @RequestBody CreateMatchTaskRequest body) {
        var task = service.createTask(new CreateMatchTaskCommand(actor, body.resumeId(), body.llmProfileId(), body.jobDescriptionText(), body.idempotencyKey(), role));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(MatchTaskResponse.from(task));
    }
    @GetMapping("/{taskId}") public MatchTaskResponse get(@RequestAttribute("actorId") UUID actor, @RequestAttribute("role") UserRole role, @PathVariable UUID taskId) { return MatchTaskResponse.from(service.getTask(taskId, actor, role)); }
    @GetMapping("/{taskId}/result") public MatchResultResponse result(@RequestAttribute("actorId") UUID actor, @RequestAttribute("role") UserRole role, @PathVariable UUID taskId) { return MatchResultResponse.from(service.getResult(taskId, actor, role)); }
    public record CreateMatchTaskRequest(UUID resumeId, UUID llmProfileId, String jobDescriptionText, String idempotencyKey) {}
    public record MatchTaskResponse(UUID id, UUID resumeId, UUID llmProfileId, MatchTask.State state, int attempt, long resumeVersion, String failureCode, boolean resultAvailable, java.time.Instant createdAt, java.time.Instant updatedAt) { static MatchTaskResponse from(MatchTask t){return new MatchTaskResponse(t.getId(),t.getResumeId(),t.getLlmProfileId(),t.getState(),t.getAttempt(),t.getResumeVersion(),t.getFailureCode(),t.isResultAvailable(),t.getCreatedAt(),t.getUpdatedAt());} }
}
