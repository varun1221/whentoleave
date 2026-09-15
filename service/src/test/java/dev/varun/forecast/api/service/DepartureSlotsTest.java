package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class DepartureSlotsTest {

    /** TomTom rejects a departAt in the past, so the search never starts from today. */
    @Test
    void startsTomorrowSoEveryDepartureIsInTheFuture() {
        ZonedDateTime now = ZonedDateTime.parse("2026-09-14T23:00:00-07:00[America/Los_Angeles]");
        for (DayOfWeek day : DayOfWeek.values()) {
            ZonedDateTime slot = DepartureSlots.nextOccurrence(day, 6, now);
            assertTrue(slot.isAfter(now), day + " slot must be in the future");
        }
    }

    @Test
    void picksTomorrowWhenTomorrowIsTheRequestedDay() {
        // 2026-09-14 is a Monday, so tomorrow is Tuesday.
        ZonedDateTime now = ZonedDateTime.parse("2026-09-14T09:00:00-07:00[America/Los_Angeles]");
        ZonedDateTime slot = DepartureSlots.nextOccurrence(DayOfWeek.TUESDAY, 8, now);

        assertEquals("2026-09-15T08:00", slot.toLocalDateTime().toString());
    }

    /** Today's weekday is a week away, not zero days, because the search starts tomorrow. */
    @Test
    void requestingTodaysWeekdayGivesNextWeek() {
        ZonedDateTime now = ZonedDateTime.parse("2026-09-14T09:00:00-07:00[America/Los_Angeles]");
        ZonedDateTime slot = DepartureSlots.nextOccurrence(DayOfWeek.MONDAY, 8, now);

        assertEquals("2026-09-21T08:00", slot.toLocalDateTime().toString());
    }

    @Test
    void keepsTheRequestedLocalHour() {
        ZonedDateTime now = ZonedDateTime.parse("2026-09-14T09:00:00-07:00[America/Los_Angeles]");
        for (int hour : new int[] {6, 10, 15, 18}) {
            assertEquals(hour,
                    DepartureSlots.nextOccurrence(DayOfWeek.THURSDAY, hour, now).getHour());
        }
    }

    /**
     * The DST point. Arithmetic is done against the zone, so 08:00 local stays 08:00
     * local across the spring transition and the UTC offset moves instead. A fixed
     * offset would silently shift every sample by an hour for half the year.
     */
    @Test
    void springForwardMovesTheOffsetNotTheLocalHour() {
        // 2026-03-08 is the US spring-forward date.
        ZonedDateTime before = ZonedDateTime.parse("2026-03-06T09:00:00-08:00[America/Los_Angeles]");
        ZonedDateTime slot = DepartureSlots.nextOccurrence(DayOfWeek.SUNDAY, 8, before);

        assertEquals(8, slot.getHour(), "local hour must survive the transition");
        assertEquals("2026-03-08", slot.toLocalDate().toString());
        assertEquals("-07:00", slot.getOffset().getId(), "should be PDT, not PST");
    }

    @Test
    void fallBackMovesTheOffsetNotTheLocalHour() {
        // 2026-11-01 is the US fall-back date.
        ZonedDateTime before = ZonedDateTime.parse("2026-10-30T09:00:00-07:00[America/Los_Angeles]");
        ZonedDateTime slot = DepartureSlots.nextOccurrence(DayOfWeek.SUNDAY, 8, before);

        assertEquals(8, slot.getHour());
        assertEquals("2026-11-01", slot.toLocalDate().toString());
        assertEquals("-08:00", slot.getOffset().getId(), "should be PST, not PDT");
    }
}
