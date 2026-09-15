package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * The daily limits reset at Pacific midnight, not UTC midnight: UTC midnight is 17:00
 * in the Bay Area, the middle of the evening peak, and "paused until tomorrow" should
 * mean what a local visitor thinks it means.
 */
class QuotaDayTest {

    private static QuotaDay at(String instant) {
        return new QuotaDay(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void lateEveningPacificIsStillTodayEvenThoughUtcHasRolledOver() {
        // 2026-09-15 23:30 PDT
        QuotaDay day = at("2026-09-16T06:30:00Z");

        assertEquals(LocalDate.of(2026, 9, 15), day.today());
        assertEquals(Instant.parse("2026-09-16T07:00:00Z"), day.resetsAt());
    }

    @Test
    void justAfterPacificMidnightIsTheNextDay() {
        // 2026-09-16 00:00:01 PDT
        QuotaDay day = at("2026-09-16T07:00:01Z");

        assertEquals(LocalDate.of(2026, 9, 16), day.today());
        assertEquals(Instant.parse("2026-09-17T07:00:00Z"), day.resetsAt());
    }

    /** Clocks fall back on 2026-11-01, so that midnight is at UTC-8, not UTC-7. */
    @Test
    void theResetFollowsDaylightSavingRatherThanAFixedOffset() {
        // 2026-10-31 12:00 PDT
        QuotaDay day = at("2026-10-31T19:00:00Z");

        assertEquals(Instant.parse("2026-11-01T07:00:00Z"), day.resetsAt());

        // 2026-11-01 12:00 PST
        assertEquals(Instant.parse("2026-11-02T08:00:00Z"),
                at("2026-11-01T20:00:00Z").resetsAt());
    }
}
