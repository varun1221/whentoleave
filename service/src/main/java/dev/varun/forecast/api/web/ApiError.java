package dev.varun.forecast.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.varun.forecast.api.service.ApiCode;
import java.time.Instant;

/**
 * The body every refusal shares: a code to switch on and a line to show.
 *
 * <p>One declared shape rather than a map assembled at each throw site, so the contract
 * is readable in one place and the origin filter — which runs before Spring MVC and so
 * cannot reach {@link ApiExceptionHandler} — answers in the same shape as everything
 * else instead of hand-writing JSON.
 *
 * <p>The trailing fields are the ones only some refusals can fill: a limit has a size
 * and a reset time, the kill switch has neither. Absent rather than null on the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(ApiCode error, String message, Integer remaining,
        Integer dailyLimit, Instant resetsAt) {

    public static ApiError of(ApiCode error, String message) {
        return new ApiError(error, message, null, null, null);
    }

    /** A global limit: how much of the shared budget is left, and when it refills. */
    public static ApiError globalLimit(ApiCode error, String message, int remaining,
            Instant resetsAt) {
        return new ApiError(error, message, remaining, null, resetsAt);
    }

    /** A per-visitor limit: how many they get a day, and when they get more. */
    public static ApiError visitorLimit(String message, int dailyLimit, Instant resetsAt) {
        return new ApiError(ApiCode.RATE_LIMITED, message, null, dailyLimit, resetsAt);
    }
}
