package dev.varun.forecast.api.service;

import java.time.Instant;

/**
 * Thrown when a lookup needs API calls and the daily ceiling leaves none. Mapped to 429
 * with a body the UI renders as "live lookups paused until tomorrow", rather than an
 * opaque failure — visible limits read as intentional design, silent ones read as broken.
 */
public class QuotaExhaustedException extends RuntimeException {

    private final String code;
    private final int remaining;
    private final Instant resetsAt;

    private QuotaExhaustedException(String code, String message, int remaining,
            Instant resetsAt) {
        super(message);
        this.code = code;
        this.remaining = remaining;
        this.resetsAt = resetsAt;
    }

    /** The shared daily budget is spent. It refills at {@code resetsAt}. */
    public static QuotaExhaustedException budgetSpent(int remaining, Instant resetsAt) {
        return new QuotaExhaustedException("quota_exhausted",
                "Live lookups are paused until tomorrow", remaining, resetsAt);
    }

    /**
     * Someone threw the kill switch. A different state from a spent budget, and the UI
     * says something different about it, so it carries a different code.
     */
    public static QuotaExhaustedException lookupsPaused(int remaining) {
        return new QuotaExhaustedException("lookups_paused",
                "Live lookups are paused; showing cached corridors only", remaining, null);
    }

    public String code() {
        return code;
    }

    public int remaining() {
        return remaining;
    }

    /** Null for the kill switch, which has no scheduled end. */
    public Instant resetsAt() {
        return resetsAt;
    }
}
