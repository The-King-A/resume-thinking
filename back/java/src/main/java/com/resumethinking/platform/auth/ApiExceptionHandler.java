package com.resumethinking.platform.auth;
import com.resumethinking.platform.profiles.ResourceNotFoundException; import com.resumethinking.platform.resumes.VersionConflictException; import com.resumethinking.platform.resumes.InvalidConfirmationException; import jakarta.persistence.OptimisticLockException; import org.springframework.dao.OptimisticLockingFailureException; import org.springframework.orm.ObjectOptimisticLockingFailureException; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import org.springframework.web.bind.MethodArgumentNotValidException; import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException; import org.springframework.http.converter.HttpMessageNotReadableException; import java.util.*;
import com.resumethinking.platform.matching.*;
@RestControllerAdvice public class ApiExceptionHandler {
 @ExceptionHandler(DuplicateResourceException.class) ResponseEntity<ApiError> duplicate(){return error(HttpStatus.CONFLICT,"DUPLICATE_RESOURCE");}
 @ExceptionHandler(IdempotencyConflictException.class) ResponseEntity<ApiError> idempotencyConflict(){return error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");}
 @ExceptionHandler(TaskGoneException.class) ResponseEntity<ApiError> taskGone(){return error(HttpStatus.GONE,"TASK_GONE");}
 @ExceptionHandler(TaskNotReadyException.class) ResponseEntity<ApiError> taskNotReady(){return error(HttpStatus.CONFLICT,"TASK_NOT_READY");}
 @ExceptionHandler(StaleAttemptException.class) ResponseEntity<ApiError> staleAttempt(){return error(HttpStatus.CONFLICT,"STALE_ATTEMPT");}
 @ExceptionHandler(EvidenceReferenceException.class) ResponseEntity<ApiError> evidenceReference(){return error(HttpStatus.BAD_REQUEST,"MODEL_OUTPUT_INVALID");}
 @ExceptionHandler(AuthenticationException.class) ResponseEntity<ApiError> auth(){return error(HttpStatus.UNAUTHORIZED,"AUTHENTICATION_REQUIRED");}
 @ExceptionHandler(ResourceNotFoundException.class) ResponseEntity<ApiError> notFound(){return error(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");}
 @ExceptionHandler(VersionConflictException.class) ResponseEntity<ApiError> versionConflict(){return error(HttpStatus.CONFLICT,"VERSION_CONFLICT");}
 @ExceptionHandler({OptimisticLockException.class,OptimisticLockingFailureException.class,ObjectOptimisticLockingFailureException.class}) ResponseEntity<ApiError> optimisticLock(){return error(HttpStatus.CONFLICT,"VERSION_CONFLICT");}
 @ExceptionHandler(InvalidConfirmationException.class) ResponseEntity<ApiError> invalidConfirmation(){return error(HttpStatus.BAD_REQUEST,"INVALID_CONFIRMATION");}
 @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class) ResponseEntity<ApiError> forbidden(){return error(HttpStatus.FORBIDDEN,"FORBIDDEN");}
 @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<ApiError> validation(){return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR");}
 @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class}) ResponseEntity<ApiError> malformed(){return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR");}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ApiError> illegalArgument(IllegalArgumentException exception){if(exception.getMessage()!=null&&exception.getMessage().contains("MODEL_ENDPOINT_REJECTED"))return error(HttpStatus.UNPROCESSABLE_ENTITY,"MODEL_ENDPOINT_REJECTED"); return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR");}
 private ResponseEntity<ApiError> error(HttpStatus status,String code){return ResponseEntity.status(status).body(new ApiError(code,code,UUID.randomUUID(),false));}
 public record ApiError(String code,String message,UUID correlationId,boolean retryable){}
}
