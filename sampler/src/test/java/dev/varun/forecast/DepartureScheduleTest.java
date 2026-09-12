package dev.varun.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.DepartureSchedule.Slot;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DepartureScheduleTest {

    @Test
    void buildsNinetyOneSlots() {
        List<Slot> slots = DepartureSchedule.nextSevenDays(LocalDate.of(2026, 6, 1));
        assertEquals(91, slots.size());
        assertEquals(91, DepartureSchedule.SLOTS_PER_ROUTE);
    }

    @Test
    void startsTomorrowSoEveryDepartureIsInTheFuture() {
        LocalDate today = LocalDate.of(2026, 6, 1);
        List<Slot> slots = DepartureSchedule.nextSevenDays(today);
        assertTrue(slots.stream()
                .allMatch(s -> s.local().toLocalDate().isAfter(today)));
        assertEquals(today.plusDays(1), slots.get(0).local().toLocalDate());
        assertEquals(today.plusDays(7), slots.get(90).local().toLocalDate());
    }

    @Test
    void coversSixToEighteenInclusive() {
        List<Slot> firstDay = DepartureSchedule.nextSevenDays(LocalDate.of(2026, 6, 1))
                .subList(0, DepartureSchedule.HOURS_PER_DAY);
        assertEquals(13, firstDay.size());
        assertEquals(6, firstDay.get(0).slotHour());
        assertEquals(18, firstDay.get(12).slotHour());
    }

    /**
     * Spring forward: 2026-03-08. 08:00 local is UTC-8 the day before and UTC-7 the day
     * after. A fixed offset would put both at the same instant and silently shift half
     * the week's samples by an hour.
     */
    @Test
    void springForwardShiftsTheUtcInstantNotTheLocalHour() {
        Map<String, Slot> eightAm = eightAmByDate(LocalDate.of(2026, 3, 6));
        assertEquals("2026-03-07T16:00:00Z", eightAm.get("2026-03-07").departureTimeUtc());
        assertEquals("2026-03-08T15:00:00Z", eightAm.get("2026-03-08").departureTimeUtc());
    }

    /** Fall back: 2026-11-01, the same test in the other direction. */
    @Test
    void fallBackShiftsTheUtcInstantNotTheLocalHour() {
        Map<String, Slot> eightAm = eightAmByDate(LocalDate.of(2026, 10, 30));
        assertEquals("2026-10-31T15:00:00Z", eightAm.get("2026-10-31").departureTimeUtc());
        assertEquals("2026-11-01T16:00:00Z", eightAm.get("2026-11-01").departureTimeUtc());
    }

    @Test
    void localLabelKeepsTheLocalHourAcrossTheTransition() {
        Map<String, Slot> eightAm = eightAmByDate(LocalDate.of(2026, 3, 6));
        assertEquals("2026-03-07T08:00", eightAm.get("2026-03-07").targetLocal());
        assertEquals("2026-03-08T08:00", eightAm.get("2026-03-08").targetLocal());
    }

    private static Map<String, Slot> eightAmByDate(LocalDate today) {
        return DepartureSchedule.nextSevenDays(today).stream()
                .filter(s -> s.slotHour() == 8)
                .collect(Collectors.toMap(
                        s -> s.local().toLocalDate().toString(), Function.identity()));
    }
}
