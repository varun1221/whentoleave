package dev.varun.forecast.api.service;

/**
 * Thrown when a lookup needs API calls and the daily ceiling leaves none. Mapped to 429
 * with a body the UI renders as "live lookups paused until tomorrow", rather than an
 * opaque failure — visible limits read as intentional design, silent ones read as broken.
 */
public class QuotaExhaustedException extends RuntimeException {

    private final int remaining;

    public QuotaExhaustedException(String message, int remaining) {
        super(message);
        this.remaining = remaining;
    }

    public int remaining() {
        return remaining;
    }
}
