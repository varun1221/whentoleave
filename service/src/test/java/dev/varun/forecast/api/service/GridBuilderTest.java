package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.varun.forecast.api.domain.Corridor;
import dev.varun.forecast.api.domain.Sample;
import dev.varun.forecast.api.service.ForecastGrid.Bucket;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class GridBuilderTest {

    private final GridBuilder builder = new GridBuilder();

    @Test
    void medianOfOddCountIsTheMiddleValue() {
        assertEquals(20L, GridBuilder.median(List.of(10L, 20L, 30L)));
    }

    @Test
    void medianOfEvenCountAveragesTheMiddleTwo() {
        assertEquals(25L, GridBuilder.median(List.of(10L, 20L, 30L, 40L)));
    }

    @Test
    void medianOfNothingIsNull() {
        assertNull(GridBuilder.median(List.of()));
        assertNull(GridBuilder.median(null));
    }

    /** Why median and not mean: one bad Tuesday should not redefine Tuesdays. */
    @Test
    void medianIgnoresAnOutlierAMeanWouldNotSurvive() {
        assertEquals(20L, GridBuilder.median(List.of(10L, 20L, 30L, 9000L, 15L)));
    }

    @Test
    void everyRequestedDayAndHourAppearsEvenWithNoSamples() {
        ForecastGrid grid = builder.build(corridor(), List.of(),
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), List.of(6, 7, 8), true);

        assertEquals(2, grid.buckets().size());
        assertEquals(3, grid.buckets().get("MONDAY").size());
        assertEquals(List.of(6, 7, 8),
                grid.buckets().get("TUESDAY").stream().map(Bucket::slotHour).toList());
    }

    /**
     * A zero would render as an instant trip in a bright green cell, which is the most
     * confidently wrong thing a heatmap can do.
     */
    @Test
    void anEmptyBucketIsNullNotZero() {
        ForecastGrid grid = builder.build(corridor(), List.of(),
                List.of(DayOfWeek.MONDAY), List.of(6), true);

        Bucket bucket = grid.buckets().get("MONDAY").get(0);
        assertNull(bucket.medianSeconds());
        assertEquals(0, bucket.n());
    }

    @Test
    void bucketsGroupByDayAndHourAndCountSamples() {
        List<Sample> samples = List.of(
                sample(DayOfWeek.MONDAY, 6, 1000),
                sample(DayOfWeek.MONDAY, 6, 2000),
                sample(DayOfWeek.MONDAY, 7, 3000),
                sample(DayOfWeek.TUESDAY, 6, 4000));

        ForecastGrid grid = builder.build(corridor(), samples,
                List.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY), List.of(6, 7), true);

        Bucket monSix = grid.buckets().get("MONDAY").get(0);
        assertEquals(1500L, monSix.medianSeconds());
        assertEquals(2, monSix.n());
        assertEquals(3000L, grid.buckets().get("MONDAY").get(1).medianSeconds());
        assertEquals(4000L, grid.buckets().get("TUESDAY").get(0).medianSeconds());
        assertNull(grid.buckets().get("TUESDAY").get(1).medianSeconds());
        assertEquals(4, grid.sampleCount());
    }

    @Test
    void distanceIsTheMedianAcrossSamplesAndSurvivesNulls() {
        List<Sample> samples = List.of(
                sampleWithDistance(DayOfWeek.MONDAY, 6, 1000, 80_000),
                sampleWithDistance(DayOfWeek.MONDAY, 7, 1000, 82_000),
                sampleWithDistance(DayOfWeek.MONDAY, 8, 1000, null));

        ForecastGrid grid = builder.build(corridor(), samples,
                List.of(DayOfWeek.MONDAY), List.of(6, 7, 8), true);

        assertEquals(81_000, grid.distanceMeters());
    }

    @Test
    void aFreshGridCarriesNoNotice() {
        assertNull(builder.build(corridor(), List.of(), List.of(DayOfWeek.MONDAY),
                List.of(6), false).notice());
    }

    @Test
    void withNoticePreservesEverythingElse() {
        Instant resetsAt = Instant.parse("2026-09-18T07:00:00Z");
        ForecastGrid grid = builder.build(corridor(), List.of(),
                List.of(DayOfWeek.MONDAY), List.of(6), true);
        ForecastGrid flagged = grid.withNotice(ApiCode.RATE_LIMITED, resetsAt);

        assertEquals(ApiCode.RATE_LIMITED, flagged.notice());
        assertEquals(resetsAt, flagged.resetsAt());
        assertEquals(grid.buckets(), flagged.buckets());
        assertEquals(grid.partial(), flagged.partial());
        assertEquals(grid.id(), flagged.id());
    }

    private static Corridor corridor() {
        return new Corridor("test", "37.3352,-121.8811", "37.7946,-122.3999", "Test",
                true);
    }

    private static Sample sample(DayOfWeek day, int hour, int seconds) {
        return sampleWithDistance(day, hour, seconds, 80_000);
    }

    private static Sample sampleWithDistance(DayOfWeek day, int hour, int seconds,
            Integer metres) {
        return new Sample(1L, day, hour, seconds, metres, Instant.parse(
                "2026-09-13T13:00:00Z"));
    }
}
