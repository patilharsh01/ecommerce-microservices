package com.harshalpatil.ecommerce.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every error leaves a service as an RFC 9457 problem document ({@code application/problem+json}),
 * with a stable {@code code} property clients can switch on. Spring MVC's own exceptions
 * (bad JSON, unsupported method, ...) are handled by the {@link ResponseEntityExceptionHandler}
 * base class in the same format.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        return problem(ex.status(), ex.code(), ex.getMessage(), request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> handleConcurrentModification(OptimisticLockingFailureException ex,
                                                               HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The resource was modified by someone else at the same time; reload it and try again", request);
    }

    /** Bean Validation on a {@code @Validated} service or component (as opposed to an MVC handler argument). */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<Map<String, String>> errors = ex.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        "field", String.valueOf(violation.getPropertyPath()),
                        "message", violation.getMessage()))
                .toList();
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                "Request validation failed", request);
        response.getBody().setProperty("errors", errors);
        return response;
    }

    /** Last line of defence when a unique/foreign-key/check constraint catches what validation didn't. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex,
                                                            HttpServletRequest request) {
        log.info("Constraint violation on {} {}: {}", request.getMethod(), request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "CONSTRAINT_VIOLATION",
                "The request conflicts with existing data", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Something went wrong on our side. The error has been logged.", request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage())))
                .toList();
        ProblemDetail body = ex.getBody();
        body.setDetail("Request validation failed");
        body.setProperty("code", "VALIDATION_FAILED");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /**
     * Raised instead of {@link MethodArgumentNotValidException} when the handler method has
     * constraints on several parameters (e.g. a validated header next to a {@code @Valid} body).
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                parameterErrors.getFieldErrors().forEach(error -> errors.add(Map.of(
                        "field", error.getField(),
                        "message", String.valueOf(error.getDefaultMessage()))));
            } else {
                String parameter = String.valueOf(result.getMethodParameter().getParameterName());
                result.getResolvableErrors().forEach(error -> errors.add(Map.of(
                        "field", parameter,
                        "message", String.valueOf(error.getDefaultMessage()))));
            }
        }
        ProblemDetail body = ex.getBody();
        body.setDetail("Request validation failed");
        body.setProperty("code", "VALIDATION_FAILED");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail,
                                                         HttpServletRequest request) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setInstance(URI.create(request.getRequestURI()));
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
