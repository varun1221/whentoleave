package dev.varun.forecast.api.service;

import java.time.Instant;

/**
 * Thrown when a lookup needs API calls and it may not make them.
 *
 * <p>Two states, not one: the shared daily budget is spent, or someone threw the kill
 * switch. They are separate because the UI says something different about each — one
 * ends at a known time and the other does not — so each carries its own {@link ApiCode}
 * and the reset time is present only when there is one.
 *
 * <p>Mapped to 429 with a body the UI renders as a banner rather than an opaque failure:
 * visible limits read as intentional design, silent ones read as broken.
 */
public class LookupsUnavailableException extends RuntimeException {

    private final ApiCode code;
    private final int remaining;
    private final Instant resetsAt;

    private LookupsUnavailableException(ApiCode code, String message, int remaining,
            Instant resetsAt) {
        super(message);
        this.code = code;
        this.remaining = remaining;
        this.resetsAt = resetsAt;
    }

    /** The shared daily budget is spent. It refills at {@code resetsAt}. */
    public static LookupsUnavailableException budgetSpent(int remaining, Instant resetsAt) {
        return new LookupsUnavailableException(ApiCode.QUOTA_EXHAUSTED,
                "Live lookups are paused until tomorrow", remaining, resetsAt);
    }

    /** Someone threw the kill switch. It ends when a human says so, not at midnight. */
    public static LookupsUnavailableException lookupsPaused(int remaining) {
        return new LookupsUnavailableException(ApiCode.LOOKUPS_PAUSED,
                "Live lookups are paused; showing cached corridors only", remaining, null);
    }

    public ApiCode code() {
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
