package com.resumethinking.platform.auth;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.resumethinking.platform.matching.*;
import com.resumethinking.platform.interviews.*;
import com.resumethinking.platform.profiles.ResourceNotFoundException;
import com.resumethinking.platform.resumes.DuplicateResumeTitleException;
import com.resumethinking.platform.resumes.InvalidConfirmationException;
import com.resumethinking.platform.resumes.ResumeNotEffectiveException;
import com.resumethinking.platform.resumes.VersionConflictException;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DuplicateResourceException.class)
    ResponseEntity<?> duplicate() { return error(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE"); }

    @ExceptionHandler(DuplicateResumeTitleException.class)
    ResponseEntity<?> duplicateResumeTitle() {
        return response(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE",
                "A resume with this title already exists.", "DUPLICATE_RESUME_TITLE",
                List.of(new ValidationDetail("title", "A resume title must be unique for this owner.")));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<?> integrity(DataIntegrityViolationException exception) {
        if (isDuplicateResumeTitleViolation(exception)) return duplicateResumeTitle();
        throw exception;
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<?> idempotencyConflict() { return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT"); }
    @ExceptionHandler(TaskGoneException.class)
    ResponseEntity<?> taskGone() { return error(HttpStatus.GONE, "TASK_GONE"); }
    @ExceptionHandler(TaskNotReadyException.class)
    ResponseEntity<?> taskNotReady() { return error(HttpStatus.CONFLICT, "TASK_NOT_READY"); }
    @ExceptionHandler(StaleAttemptException.class)
    ResponseEntity<?> staleAttempt() { return error(HttpStatus.CONFLICT, "STALE_ATTEMPT"); }
    @ExceptionHandler(EvidenceReferenceException.class)
    ResponseEntity<?> evidenceReference() { return error(HttpStatus.BAD_REQUEST, "MODEL_OUTPUT_INVALID"); }
    @ExceptionHandler(InvalidCredentialsException.class)
    ResponseEntity<?> invalidCredentials() { return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS"); }
    @ExceptionHandler(ResumeNotEffectiveException.class)
    ResponseEntity<?> resumeNotEffective() { return error(HttpStatus.CONFLICT, "RESUME_NOT_EFFECTIVE"); }
    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<?> notFound() { return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND"); }
    @ExceptionHandler(VersionConflictException.class)
    ResponseEntity<?> versionConflict() { return error(HttpStatus.CONFLICT, "VERSION_CONFLICT"); }

    @ExceptionHandler({OptimisticLockException.class, OptimisticLockingFailureException.class,
            ObjectOptimisticLockingFailureException.class})
    ResponseEntity<?> optimisticLock() { return error(HttpStatus.CONFLICT, "VERSION_CONFLICT"); }
    @ExceptionHandler(InvalidConfirmationException.class)
    ResponseEntity<?> invalidConfirmation() { return error(HttpStatus.BAD_REQUEST, "INVALID_CONFIRMATION"); }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<?> forbidden() { return error(HttpStatus.FORBIDDEN, "FORBIDDEN"); }
    @ExceptionHandler(InterviewMatchNotReadyException.class)
    ResponseEntity<?> interviewMatchNotReady() { return error(HttpStatus.CONFLICT, "INTERVIEW_MATCH_NOT_READY"); }
    @ExceptionHandler(InterviewSessionNotFoundException.class)
    ResponseEntity<?> interviewNotFound() { return error(HttpStatus.NOT_FOUND, "INTERVIEW_SESSION_NOT_FOUND"); }
    @ExceptionHandler(InterviewSessionGoneException.class)
    ResponseEntity<?> interviewGone() { return error(HttpStatus.GONE, "INTERVIEW_SESSION_GONE"); }
    @ExceptionHandler(InterviewQuestionNotReadyException.class)
    ResponseEntity<?> interviewQuestionNotReady() { return error(HttpStatus.CONFLICT, "INTERVIEW_QUESTION_NOT_READY"); }
    @ExceptionHandler(InterviewFeedbackNotReadyException.class)
    ResponseEntity<?> interviewFeedbackNotReady() { return error(HttpStatus.CONFLICT, "INTERVIEW_FEEDBACK_NOT_READY"); }
    @ExceptionHandler(InterviewSessionFailedException.class)
    ResponseEntity<?> interviewSessionFailed(InterviewSessionFailedException exception) {
        String code = Set.of("INTERVIEW_MODEL_UNAVAILABLE", "INTERVIEW_MODEL_OUTPUT_INVALID")
                .contains(exception.getMessage()) ? exception.getMessage() : "INTERVIEW_MODEL_OUTPUT_INVALID";
        HttpStatus status = "INTERVIEW_MODEL_UNAVAILABLE".equals(code)
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
        return error(status, code);
    }
    @ExceptionHandler(InterviewAnswerConflictException.class)
    ResponseEntity<?> interviewAnswerConflict() { return error(HttpStatus.CONFLICT, "INTERVIEW_ANSWER_CONFLICT"); }
    @ExceptionHandler(InterviewCallbackStaleException.class)
    ResponseEntity<?> interviewCallbackStale() { return error(HttpStatus.CONFLICT, "INTERVIEW_CALLBACK_STALE"); }
    @ExceptionHandler(InterviewModelOutputInvalidException.class)
    ResponseEntity<?> interviewOutputInvalid() { return error(HttpStatus.BAD_REQUEST, "INTERVIEW_MODEL_OUTPUT_INVALID"); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException exception) {
        List<ValidationDetail> details = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new ValidationDetail(error.getField(), "invalid")).distinct().toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "VALIDATION_ERROR", null, details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<?> malformed() { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> illegalArgument(IllegalArgumentException exception) {
        String code = exception.getMessage();
        if ("MODEL_ENDPOINT_REJECTED".equals(code)) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "MODEL_ENDPOINT_REJECTED");
        }
        if ("UNSUPPORTED_FILE".equals(code)) return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE");
        if ("PAYLOAD_TOO_LARGE".equals(code)) return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<?> maxUploadSize() { return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE"); }

    private ResponseEntity<?> error(HttpStatus status, String code) {
        return response(status, code, code, null, List.of());
    }

    private ResponseEntity<?> response(HttpStatus status, String code, String message,
                                       String detailCode, List<ValidationDetail> details) {
        return ResponseEntity.status(status).body(errorBodyForPath(currentRequestPath(), code, message,
                detailCode, details));
    }

    public static Object errorBodyForPath(String path, String code, String message,
                                          String detailCode, List<ValidationDetail> details) {
        UUID correlationId = UUID.randomUUID();
        if (isV3Path(path) || isV4Path(path)) {
            return new V3ApiError(code, message, correlationId, false, detailCode, details);
        }
        // V2 clients use the detail code to distinguish a recoverable title
        // collision from a generic duplicate resource. Keep the legacy V1
        // envelope unchanged while preserving the field for V2 responses.
        return new ApiError(code, message, correlationId, false,
                isV2Path(path) ? detailCode : null, details);
    }

    public static Object authenticationErrorForPath(String path) {
        return errorBodyForPath(path, "AUTHENTICATION_REQUIRED", "AUTHENTICATION_REQUIRED", null, List.of());
    }

    private static String currentRequestPath() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return attributes.getRequest().getRequestURI();
        }
        return null;
    }

    private static boolean isV3Path(String path) {
        return path != null && (path.startsWith("/api/v3/") || path.contains("/api/v3/")
                || path.startsWith("/internal/v3/") || path.contains("/internal/v3/"));
    }

    private static boolean isV2Path(String path) {
        return path != null && (path.startsWith("/api/v2/") || path.contains("/api/v2/")
                || path.startsWith("/internal/v2/") || path.contains("/internal/v2/"));
    }

    private static boolean isV4Path(String path) {
        return path != null && (path.startsWith("/api/v4/") || path.contains("/api/v4/")
                || path.startsWith("/internal/v4/") || path.contains("/internal/v4/"));
    }

    public record ApiError(String code, String message, UUID correlationId, boolean retryable,
                           @JsonInclude(JsonInclude.Include.NON_NULL) String detailCode,
                           List<ValidationDetail> details) {
        public ApiError(String code, String message, UUID correlationId, boolean retryable) {
            this(code, message, correlationId, retryable, null, List.of());
        }
    }

    public record V3ApiError(String code, String message, UUID correlationId, boolean retryable,
                             String detailCode, List<ValidationDetail> details) {}
    public record ValidationDetail(String field, String reason) {}

    private static boolean isDuplicateResumeTitleViolation(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT)
                    .contains("uq_resumes_owner_effective_title")) return true;
        }
        return false;
    }
}
