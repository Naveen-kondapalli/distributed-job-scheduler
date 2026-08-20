package com.jobservice.exception;

import com.jobservice.cancellation.CancellationSignalException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ApiErrorResponse> handleApplicationException(
            ApplicationException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                exception.getStatus(),
                exception.getErrorCode(),
                exception.getMessage(),
                request,
                List.of()
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<FieldValidationError> fieldErrors = exception.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> new FieldValidationError(error.getField(), error.getDefaultMessage()))
                .sorted(Comparator.comparing(FieldValidationError::field))
                .toList();

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_FAILED,
                "Request validation failed",
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolationException(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<FieldValidationError> fieldErrors = exception.getConstraintViolations()
                .stream()
                .map(violation -> new FieldValidationError(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()
                ))
                .sorted(Comparator.comparing(FieldValidationError::field))
                .toList();

        return buildResponse(
                HttpStatus.BAD_REQUEST,
                ErrorCode.VALIDATION_FAILED,
                "Request validation failed",
                request,
                fieldErrors
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadableException(
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                ErrorCode.MALFORMED_REQUEST,
                "Request body is missing or malformed",
                request,
                List.of()
        );
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> handleBadCredentialsException(HttpServletRequest request) {
        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                ErrorCode.INVALID_CREDENTIALS,
                "Invalid email or password",
                request,
                List.of()
        );
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiErrorResponse> handleAuthenticationException(HttpServletRequest request) {
        return buildResponse(
                HttpStatus.UNAUTHORIZED,
                ErrorCode.UNAUTHORIZED,
                "Authentication is required",
                request,
                List.of()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiErrorResponse> handleAccessDeniedException(HttpServletRequest request) {
        return buildResponse(
                HttpStatus.FORBIDDEN,
                ErrorCode.ACCESS_DENIED,
                "You do not have permission to access this resource",
                request,
                List.of()
        );
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrityViolationException(HttpServletRequest request) {
        return buildResponse(
                HttpStatus.CONFLICT,
                ErrorCode.DATA_INTEGRITY_VIOLATION,
                "The request conflicts with existing data",
                request,
                List.of()
        );
    }

    @ExceptionHandler(CancellationSignalException.class)
    public ResponseEntity<ApiErrorResponse> handleCancellationSignalException(
            CancellationSignalException exception,
            HttpServletRequest request
    ) {
        LOGGER.warn("Cancellation signal delivery failed for {} {}", request.getMethod(), request.getRequestURI(), exception);
        return buildResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                ErrorCode.CANCELLATION_SIGNAL_FAILED,
                exception.getMessage(),
                request,
                List.of()
        );
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFoundException(HttpServletRequest request) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                ErrorCode.RESOURCE_NOT_FOUND,
                "Resource not found",
                request,
                List.of()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleException(Exception exception, HttpServletRequest request) {
        LOGGER.error("Unexpected server error while handling {} {}", request.getMethod(), request.getRequestURI(), exception);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                ErrorCode.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                request,
                List.of()
        );
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status,
            ErrorCode errorCode,
            String message,
            HttpServletRequest request,
            List<FieldValidationError> fieldErrors
    ) {
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                errorCode.name(),
                message,
                request.getRequestURI(),
                fieldErrors
        );
        return ResponseEntity.status(status).body(body);
    }
}
