package dev.varun.forecast.api.service;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The machine-readable reasons the API gives for refusing, or for answering with less
 * than it should have.
 *
 * <p>An enum rather than string literals because these values are a contract with the
 * frontend, and the same reason has to spell the same in both places it appears: as
 * {@code error} in a 4xx body, and as {@code notice} on a 200 that could not be filled
 * completely. A mistyped literal is a banner that silently never renders, which is the
 * kind of bug nothing fails on.
 */
public enum ApiCode {

    /** Someone threw the kill switch. Unlike the others, no scheduled end. */
    LOOKUPS_PAUSED("lookups_paused"),

    /** The global daily ceiling is spent. Refills at Pacific midnight. */
    QUOTA_EXHAUSTED("quota_exhausted"),

    /** This visitor has used their own daily budget. */
    RATE_LIMITED("rate_limited"),

    /** The request did not come through Cloudflare, so its client IP is not trustworthy. */
    FORBIDDEN("forbidden"),

    /** The request itself was malformed. */
    INVALID_REQUEST("invalid_request");

    private final String wire;

    ApiCode(String wire) {
        this.wire = wire;
    }

    /** What goes on the wire. The frontend matches on exactly this. */
    @JsonValue
    public String wire() {
        return wire;
    }
}
