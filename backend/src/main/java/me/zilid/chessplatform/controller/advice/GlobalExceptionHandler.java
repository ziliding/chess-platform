package me.zilid.chessplatform.controller.advice;

import java.util.*;
import me.zilid.chessplatform.exception.AnalysisUnavailableException;
import me.zilid.chessplatform.exception.FriendAlreadyExistsException;
import me.zilid.chessplatform.exception.GameIsOverException;
import me.zilid.chessplatform.exception.GameNotFoundException;
import me.zilid.chessplatform.exception.TooManyAnalysesException;
import me.zilid.chessplatform.exception.UserNotFoundException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // Keep Spring's handling of MVC errors (400, 404, 405, 415, etc.) and their headers.
    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "The request contains invalid values");
        problem.setTitle("Validation failed");
        Map<String, List<String>> errors = new LinkedHashMap<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(error -> errors.computeIfAbsent(error.getField(), _ -> new ArrayList<>())
                        .add(Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid value")));
        problem.setProperty("errors", errors);
        if (ex.getBindingResult().hasGlobalErrors()) {
            problem.setProperty(
                    "globalErrors",
                    ex.getBindingResult().getGlobalErrors().stream()
                            .map(error -> Objects.requireNonNullElse(error.getDefaultMessage(), "Invalid value"))
                            .toList());
        }
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "Invalid request body");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        // Generic exceptions may come from libraries; their messages are not a public API.
        logger.debug("Invalid request argument: {}", ex.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid request");
    }

    @ExceptionHandler({GameNotFoundException.class, UserNotFoundException.class})
    public ProblemDetail handleNotFound(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({FriendAlreadyExistsException.class, GameIsOverException.class})
    public ProblemDetail handleConflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(AnalysisUnavailableException.class)
    public ProblemDetail handleAnalysisUnavailable(AnalysisUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        problem.setProperty("code", ex.getCode());
        return problem;
    }

    @ExceptionHandler(TooManyAnalysesException.class)
    public ProblemDetail handleTooManyAnalyses(TooManyAnalysesException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ProblemDetail handleBadCredentials(BadCredentialsException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid username or password");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "You do not have permission to perform this action");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex) {
        logger.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Request conflicts with existing data");
    }

    // Includes unexpected IllegalStateException: a programming failure is not a client conflict.
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpectedException(Exception ex) {
        logger.error("Unexpected error occurred", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}
