package dev.varun.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.varun.forecast.model.Bucket;
import dev.varun.forecast.model.Sample;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AggregatorTest {

    @Test
    void medianOfOddCountIsTheMiddleValue() {
        assertEquals(200L, Aggregator.median(List.of(300L, 100L, 200L)));
    }

    @Test
    void medianOfEvenCountAveragesTheMiddleTwo() {
        assertEquals(250L, Aggregator.median(List.of(100L, 200L, 300L, 400L)));
    }

    @Test
    void medianIgnoresAnOutlierAMeanWouldNotSurvive() {
        // Four ordinary Tuesdays and one crash. A mean would report 26 minutes.
        List<Long> durations = List.of(1800L, 1820L, 1790L, 1810L, 7200L);
        assertEquals(1810L, Aggregator.median(durations));
    }

    @Test
    void medianOfNothingIsNull() {
        assertNull(Aggregator.median(List.of()));
    }

    @Test
    void everyDayAndHourIsPresentEvenWithNoSamples() {
        Map<String, List<Bucket>> buckets = Aggregator.bucket(List.of());
        assertEquals(7, buckets.size());
        for (List<Bucket> row : buckets.values()) {
            assertEquals(13, row.size());
        }
    }

    @Test
    void emptyBucketIsNullNotZero() {
        Map<String, List<Bucket>> buckets = Aggregator.bucket(List.of());
        Bucket cell = buckets.get("MONDAY").get(0);
        assertNull(cell.medianSeconds(), "a zero here would render as an instant trip");
        assertEquals(0, cell.n());
    }

    @Test
    void bucketsGroupByDayAndHourAndCountSamples() {
        List<Sample> samples = List.of(
                sample("MONDAY", 8, 1800),
                sample("MONDAY", 8, 2000),
                sample("MONDAY", 9, 1200),
                sample("FRIDAY", 17, 3000));
        Map<String, List<Bucket>> buckets = Aggregator.bucket(samples);

        Bucket mon8 = buckets.get("MONDAY").get(2);
        assertEquals(8, mon8.slotHour());
        assertEquals(1900L, mon8.medianSeconds());
        assertEquals(2, mon8.n());

        Bucket mon9 = buckets.get("MONDAY").get(3);
        assertEquals(1200L, mon9.medianSeconds());
        assertEquals(1, mon9.n());

        Bucket fri17 = buckets.get("FRIDAY").get(11);
        assertEquals(17, fri17.slotHour());
        assertEquals(3000L, fri17.medianSeconds());

        assertNull(buckets.get("SUNDAY").get(0).medianSeconds());
    }

    @Test
    void weeksSampledCountsDistinctIsoWeeks() {
        List<Sample> samples = List.of(
                sampleAt("2026-08-30T09:02:11Z"),
                sampleAt("2026-09-06T09:02:11Z"),  // the next weekly sweep
                sampleAt("2026-09-13T09:02:11Z"),  // and the one after
                sampleAt("2026-09-13T10:02:11Z")); // same sweep, second route
        assertEquals(3, Aggregator.weeksSampled(samples));
    }

    private static Sample sample(String day, int hour, long seconds) {
        return new Sample("2026-09-02T" + hour + ":00", day, hour, seconds, 78234L,
                "2026-08-30T09:02:11Z");
    }

    private static Sample sampleAt(String requestedAt) {
        return new Sample("2026-09-02T08:00", "WEDNESDAY", 8, 1800, 78234L, requestedAt);
    }
}
