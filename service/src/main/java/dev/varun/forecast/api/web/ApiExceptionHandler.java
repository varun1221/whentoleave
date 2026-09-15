package dev.varun.forecast.api.web;

import dev.varun.forecast.api.service.QuotaExhaustedException;
import dev.varun.forecast.api.service.RateLimitedException;
import java.util.LinkedHashMap;
import java.util.Map;
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

    @ExceptionHandler(QuotaExhaustedException.class)
    public ResponseEntity<Map<String, Object>> quotaExhausted(QuotaExhaustedException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        body.put("message", e.getMessage());
        body.put("remaining", e.remaining());
        if (e.resetsAt() != null) {
            body.put("resetsAt", e.resetsAt());
        }
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Map<String, Object>> rateLimited(RateLimitedException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of(
                        "error", "rate_limited",
                        "message", e.getMessage(),
                        "dailyLimit", e.dailyLimit(),
                        "resetsAt", e.resetsAt()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(Map.of("error", "invalid_request", "message", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidBody(
            MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("Invalid request body");
        return ResponseEntity.badRequest()
                .body(Map.of("error", "invalid_request", "message", message));
    }
}
