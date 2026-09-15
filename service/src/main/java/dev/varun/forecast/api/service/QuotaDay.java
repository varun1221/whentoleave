package dev.varun.forecast.api.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.stereotype.Component;

/**
 * The day both daily limits count against, and when it ends.
 *
 * <p>Pacific rather than UTC: UTC midnight is 17:00 in the Bay Area, the middle of the
 * evening peak, so a UTC day would refill the budget at the worst possible moment and
 * make "paused until tomorrow" mean something no local visitor expects. One definition
 * shared by the global counter and the per-IP one, so there is a single reset time to
 * show in the UI.
 */
@Component
public class QuotaDay {

    public static final ZoneId ZONE = DepartureSlots.ZONE;

    private final Clock clock;

    public QuotaDay(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(ZONE));
    }

    /** The next Pacific midnight, zone-aware so it moves with daylight saving. */
    public Instant resetsAt() {
        return today().plusDays(1).atStartOfDay(ZONE).toInstant();
    }
}
