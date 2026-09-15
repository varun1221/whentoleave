package dev.varun.forecast.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;

/**
 * One observed duration for a corridor at a (day-of-week, hour) slot.
 *
 * <p>This table doubles as the cache: a lookup that finds recent enough rows here costs
 * zero API calls. {@code requestedAt} is therefore load-bearing, not bookkeeping.
 */
@Entity
@Table(name = "sample")
public class Sample {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "corridor_id", nullable = false)
    private Long corridorId;

    /** ISO day number, Monday = 1 through Sunday = 7. */
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "slot_hour", nullable = false)
    private short slotHour;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    @Column(name = "distance_meters")
    private Integer distanceMeters;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    protected Sample() {}

    public Sample(Long corridorId, DayOfWeek dayOfWeek, int slotHour, int durationSeconds,
            Integer distanceMeters, Instant requestedAt) {
        this.corridorId = corridorId;
        this.dayOfWeek = (short) dayOfWeek.getValue();
        this.slotHour = (short) slotHour;
        this.durationSeconds = durationSeconds;
        this.distanceMeters = distanceMeters;
        this.requestedAt = requestedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCorridorId() {
        return corridorId;
    }

    public DayOfWeek getDayOfWeek() {
        return DayOfWeek.of(dayOfWeek);
    }

    public int getSlotHour() {
        return slotHour;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public Integer getDistanceMeters() {
        return distanceMeters;
    }

    public Instant getRequestedAt() {
        return requestedAt;
    }
}
