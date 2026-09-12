package dev.varun.forecast;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the departure slots for one sweep: the next 7 calendar days, hourly from
 * START_HOUR to END_HOUR inclusive, in America/Los_Angeles.
 *
 * <p>Slots start at <em>tomorrow</em>, not today. departureTime must be in the future
 * for the Routes API to accept it, and starting tomorrow makes the grid exactly 91 slots
 * whether the sweep fires at its 02:00 PT cron or hours late off a manual dispatch.
 *
 * <p>Arithmetic is done with {@link ZonedDateTime} against the zone, never a fixed
 * offset. A fixed -08:00 would silently shift every sample by an hour across the March
 * and November DST transitions.
 */
public final class DepartureSchedule {

    public static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    public static final int START_HOUR = 6;
    public static final int END_HOUR = 18;
    public static final int DAYS = 7;
    public static final int HOURS_PER_DAY = END_HOUR - START_HOUR + 1;
    public static final int SLOTS_PER_ROUTE = DAYS * HOURS_PER_DAY;

    private static final DateTimeFormatter LOCAL_LABEL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    private DepartureSchedule() {}

    /** A departure instant, carrying both its local identity and its UTC wire form. */
    public record Slot(ZonedDateTime local) {

        public DayOfWeek dayOfWeek() {
            return local.getDayOfWeek();
        }

        public int slotHour() {
            return local.getHour();
        }

        /** "2026-09-02T08:00" — the local label stored in the JSONL. */
        public String targetLocal() {
            return local.format(LOCAL_LABEL);
        }

        /** RFC 3339 in UTC, which is what departureTime on the wire must be. */
        public String departureTimeUtc() {
            return DateTimeFormatter.ISO_INSTANT.format(local.toInstant());
        }
    }

    /** Slots for the 7 days following {@code today}. */
    public static List<Slot> nextSevenDays(LocalDate today) {
        List<Slot> slots = new ArrayList<>(SLOTS_PER_ROUTE);
        for (int dayOffset = 1; dayOffset <= DAYS; dayOffset++) {
            LocalDate date = today.plusDays(dayOffset);
            for (int hour = START_HOUR; hour <= END_HOUR; hour++) {
                // atZone-style resolution: on a spring-forward date a nonexistent local
                // time rolls forward rather than throwing. None of 06:00-18:00 is
                // affected in this zone, but the rule is worth being explicit about.
                slots.add(new Slot(date.atTime(hour, 0).atZone(ZONE)));
            }
        }
        return slots;
    }

    /** Slots for the 7 days following today, in {@link #ZONE}. */
    public static List<Slot> nextSevenDays() {
        return nextSevenDays(LocalDate.now(ZONE));
    }
}
