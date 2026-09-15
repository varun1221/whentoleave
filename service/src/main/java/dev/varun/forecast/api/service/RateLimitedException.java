package dev.varun.forecast.api.service;

import java.time.Instant;

/**
 * This visitor has used a daily per-IP budget, lookups or address searches. Mapped to
 * 429 with the limit and the reset time, so the UI can say "you've used your 5 lookups
 * today" and when they come back, rather than showing a bare failure.
 */
public class RateLimitedException extends RuntimeException {

    private final int dailyLimit;
    private final Instant resetsAt;

    private RateLimitedException(String message, int dailyLimit, Instant resetsAt) {
        super(message);
        this.dailyLimit = dailyLimit;
        this.resetsAt = resetsAt;
    }

    /** @param what the plural noun for the budget, e.g. "lookups" */
    public static RateLimitedException used(String what, int dailyLimit, Instant resetsAt) {
        return new RateLimitedException(
                "You have used your " + dailyLimit + " " + what + " for today",
                dailyLimit, resetsAt);
    }

    public int dailyLimit() {
        return dailyLimit;
    }

    public Instant resetsAt() {
        return resetsAt;
    }
}
