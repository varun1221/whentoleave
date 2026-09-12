package dev.varun.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.DepartureSchedule.Slot;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class MainTest {

    /**
     * The spread check exists to answer "does this corridor have rush hour at all". A
     * weekend day is flat by definition, so running it on one would answer the wrong
     * question.
     */
    @Test
    void spreadPicksAWeekdayEvenWhenTomorrowIsTheWeekend() {
        // 2026-09-05 is a Saturday, so the schedule opens on Sunday.
        List<Slot> slots = DepartureSchedule.nextSevenDays(LocalDate.of(2026, 9, 5));
        assertEquals(DayOfWeek.SUNDAY, slots.get(0).dayOfWeek());

        List<Slot> day = Main.firstWeekday(slots);
        assertEquals(DayOfWeek.MONDAY, day.get(0).dayOfWeek());
        assertEquals(13, day.size());
        assertTrue(day.stream().allMatch(s -> s.dayOfWeek() == DayOfWeek.MONDAY));
        assertEquals(6, day.get(0).slotHour());
        assertEquals(18, day.get(12).slotHour());
    }

    @Test
    void spreadUsesTomorrowWhenTomorrowIsAlreadyAWeekday() {
        // 2026-09-07 is a Monday, so the schedule opens on Tuesday.
        List<Slot> day = Main.firstWeekday(
                DepartureSchedule.nextSevenDays(LocalDate.of(2026, 9, 7)));
        assertEquals(DayOfWeek.TUESDAY, day.get(0).dayOfWeek());
        assertEquals(13, day.size());
    }
}
