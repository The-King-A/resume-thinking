package com.resumethinking.platform.auth;
import com.resumethinking.platform.profiles.ResourceNotFoundException; import com.resumethinking.platform.resumes.VersionConflictException; import com.resumethinking.platform.resumes.InvalidConfirmationException; import org.springframework.http.*; import org.springframework.web.bind.annotation.*; import org.springframework.web.bind.MethodArgumentNotValidException; import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException; import org.springframework.http.converter.HttpMessageNotReadableException; import java.util.*;
@RestControllerAdvice public class ApiExceptionHandler {
 @ExceptionHandler(DuplicateResourceException.class) ResponseEntity<ApiError> duplicate(){return error(HttpStatus.CONFLICT,"DUPLICATE_RESOURCE");}
 @ExceptionHandler(AuthenticationException.class) ResponseEntity<ApiError> auth(){return error(HttpStatus.UNAUTHORIZED,"AUTHENTICATION_REQUIRED");}
 @ExceptionHandler(ResourceNotFoundException.class) ResponseEntity<ApiError> notFound(){return error(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND");}
 @ExceptionHandler(VersionConflictException.class) ResponseEntity<ApiError> versionConflict(){return error(HttpStatus.CONFLICT,"VERSION_CONFLICT");}
 @ExceptionHandler(InvalidConfirmationException.class) ResponseEntity<ApiError> invalidConfirmation(){return error(HttpStatus.BAD_REQUEST,"INVALID_CONFIRMATION");}
 @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class) ResponseEntity<ApiError> forbidden(){return error(HttpStatus.FORBIDDEN,"FORBIDDEN");}
 @ExceptionHandler(MethodArgumentNotValidException.class) ResponseEntity<ApiError> validation(){return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR");}
 @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class}) ResponseEntity<ApiError> malformed(){return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR");}
 @ExceptionHandler(IllegalArgumentException.class) ResponseEntity<ApiError> illegalArgument(IllegalArgumentException exception){if(exception.getMessage()!=null&&exception.getMessage().contains("MODEL_ENDPOINT_REJECTED"))return error(HttpStatus.UNPROCESSABLE_ENTITY,"MODEL_ENDPOINT_REJECTED"); return error(HttpStatus.BAD_REQUEST,"VALIDATION_ERROR");}
 private ResponseEntity<ApiError> error(HttpStatus status,String code){return ResponseEntity.status(status).body(new ApiError(code,code,UUID.randomUUID(),false));}
 public record ApiError(String code,String message,UUID correlationId,boolean retryable){}
}
