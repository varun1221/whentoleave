package dev.varun.forecast.api.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Turning a (day-of-week, hour) slot back into a concrete future departure time.
 *
 * <p>Zone-aware arithmetic against America/Los_Angeles, never a fixed offset — the same
 * correctness point as the Phase 1 sampler. A fixed offset silently shifts every sample
 * by an hour twice a year.
 */
public final class DepartureSlots {

    public static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");

    /**
     * The next occurrence of this slot, starting tomorrow.
     *
     * <p>TomTom requires {@code departAt} to be in the future, and starting from today
     * would produce a past time for any hour already gone.
     */
    public static ZonedDateTime nextOccurrence(DayOfWeek day, int hour, ZonedDateTime now) {
        LocalDate date = now.toLocalDate().plusDays(1);
        while (date.getDayOfWeek() != day) {
            date = date.plusDays(1);
        }
        return date.atTime(hour, 0).atZone(ZONE);
    }

    private DepartureSlots() {}
}
