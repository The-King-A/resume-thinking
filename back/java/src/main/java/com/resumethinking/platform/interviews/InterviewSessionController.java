package com.resumethinking.platform.interviews;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.resumethinking.platform.auth.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v4/interview-sessions")
public class InterviewSessionController {
    private final InterviewSessionService service;

    public InterviewSessionController(InterviewSessionService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<InterviewResponse.SessionResponse> create(
            @RequestAttribute("actorId") String actorId,
            @RequestAttribute("role") UserRole role,
            @Valid @RequestBody CreateRequest request) {
        InterviewSession session = service.create(new InterviewSessionService.CreateInterviewSessionCommand(
                actorId, role, request.matchTaskId(), request.idempotencyKey()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(InterviewResponse.SessionResponse.from(session));
    }

    @GetMapping("/{sessionId}")
    public InterviewResponse.SessionResponse get(@RequestAttribute("actorId") String actorId, @PathVariable String sessionId) {
        return InterviewResponse.SessionResponse.from(service.getSession(sessionId, actorId));
    }

    @GetMapping("/{sessionId}/questions")
    public Map<String, Object> questions(@RequestAttribute("actorId") String actorId, @PathVariable String sessionId) {
        InterviewSession session = service.getSession(sessionId, actorId);
        Set<String> answered = service.getAnsweredQuestionIds(sessionId, actorId);
        List<InterviewResponse.QuestionResponse> values = service.getQuestions(sessionId, actorId).stream().map(value ->
                InterviewResponse.QuestionResponse.from(value, evidenceIds(value.getEvidenceIdsJson()), answered.contains(value.getId()))).toList();
        return Map.of("session", InterviewResponse.SessionResponse.from(session), "questions", values);
    }

    @PostMapping("/{sessionId}/questions/regenerate")
    public ResponseEntity<InterviewResponse.SessionResponse> regenerate(@RequestAttribute("actorId") String actorId,
                                                                          @PathVariable String sessionId,
                                                                          @Valid @RequestBody RegenerateRequest request) {
        InterviewSession session = service.regenerateQuestions(sessionId, actorId, request.expectedVersion(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(InterviewResponse.SessionResponse.from(session));
    }

    @PostMapping("/{sessionId}/answers")
    public ResponseEntity<InterviewResponse.AnswerSubmissionResponse> answer(
            @RequestAttribute("actorId") String actorId, @PathVariable String sessionId,
            @Valid @RequestBody AnswerRequest request) {
        InterviewAnswer answer = service.submitAnswer(sessionId, actorId, new InterviewSessionService.SubmitInterviewAnswerCommand(
                request.questionId(), request.answerText(), request.expectedSessionVersion(), request.idempotencyKey()));
        InterviewSession session = service.getSession(sessionId, actorId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new InterviewResponse.AnswerSubmissionResponse(
                InterviewResponse.SessionResponse.from(session), answer.getId(), session.getState().name(), false));
    }

    @GetMapping("/{sessionId}/feedback")
    public Map<String, Object> feedback(@RequestAttribute("actorId") String actorId, @PathVariable String sessionId) {
        InterviewFeedback value = service.getFeedback(sessionId, actorId);
        try {
            Map<String, Object> payload = new LinkedHashMap<>(new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(value.getPayloadJson(), new com.fasterxml.jackson.core.type.TypeReference<>() { }));
            payload.remove("feedbackId");
            payload.put("id", value.getId());
            payload.put("sessionId", value.getSessionId());
            payload.put("answerId", value.getAnswerId());
            payload.put("submittedAnswer", service.getSubmittedAnswer(sessionId, actorId, value.getAnswerId()));
            payload.put("nextQuestionId", service.nextQuestionId(sessionId, actorId));
            payload.put("version", value.getFeedbackVersion());
            payload.put("createdAt", value.getCreatedAt());
            return payload;
        } catch (Exception exception) {
            throw new InterviewModelOutputInvalidException();
        }
    }

    @PostMapping("/{sessionId}/next-question")
    public InterviewResponse.SessionResponse nextQuestion(@RequestAttribute("actorId") String actorId,
                                                           @PathVariable String sessionId,
                                                           @Valid @RequestBody NextQuestionRequest request) {
        return InterviewResponse.SessionResponse.from(service.moveToNextQuestion(sessionId, actorId, request.expectedSessionVersion()));
    }

    @PostMapping("/{sessionId}/confirmations")
    public InterviewResponse.ConfirmationResponse confirm(@RequestAttribute("actorId") String actorId, @PathVariable String sessionId,
                                                            @Valid @RequestBody ConfirmationRequest request) {
        return InterviewResponse.ConfirmationResponse.from(service.confirm(sessionId, actorId, request.claimId(),
                request.decision(), request.expectedFeedbackVersion()));
    }

    @DeleteMapping("/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestAttribute("actorId") String actorId, @PathVariable String sessionId) {
        service.delete(sessionId, actorId);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CreateRequest(
            @NotBlank @Pattern(regexp = "^task[0-9]{3,}$") String matchTaskId,
            @NotBlank @Size(min = 16, max = 128) String idempotencyKey) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AnswerRequest(@NotBlank @Pattern(regexp = "^question[0-9]{3,}$") String questionId,
                                @NotBlank @Size(max = 8000) String answerText,
                                long expectedSessionVersion,
                                @NotBlank @Size(min = 16, max = 128) String idempotencyKey) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RegenerateRequest(long expectedVersion,
                                    @NotBlank @Size(min = 16, max = 128) String idempotencyKey) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ConfirmationRequest(@NotBlank @Pattern(regexp = "^claim[0-9]{3,}$") String claimId,
                                       @NotBlank @Pattern(regexp = "CONFIRMED|REJECTED") String decision,
                                       long expectedFeedbackVersion) { }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record NextQuestionRequest(long expectedSessionVersion) { }

    private static List<String> evidenceIds(String json) {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() { }); }
        catch (Exception ignored) { return List.of(); }
    }
}
