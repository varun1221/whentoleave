package dev.varun.forecast.api.web;

import dev.varun.forecast.api.service.ApiCode;
import dev.varun.forecast.api.service.LookupsUnavailableException;
import dev.varun.forecast.api.service.RateLimitedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns the two expected failures into bodies the frontend can render. Both are normal
 * states rather than errors to hide: a visitor who has hit a limit should be told what
 * the limit is.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LookupsUnavailableException.class)
    public ResponseEntity<ApiError> lookupsUnavailable(LookupsUnavailableException e) {
        // resetsAt is null for the kill switch, and drops out of the body rather than
        // promising the visitor a time that does not exist.
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiError.globalLimit(e.code(), e.getMessage(), e.remaining(),
                        e.resetsAt()));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<ApiError> rateLimited(RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiError.visitorLimit(e.getMessage(), e.dailyLimit(),
                        e.resetsAt()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(ApiCode.INVALID_REQUEST, e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalidBody(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Invalid request body");
        return ResponseEntity.badRequest()
                .body(ApiError.of(ApiCode.INVALID_REQUEST, message));
    }
}
