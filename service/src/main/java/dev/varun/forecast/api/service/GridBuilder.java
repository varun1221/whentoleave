package dev.varun.forecast.api.service;

import dev.varun.forecast.api.domain.Corridor;
import dev.varun.forecast.api.domain.Sample;
import dev.varun.forecast.api.service.ForecastGrid.Bucket;
import java.time.DayOfWeek;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Samples to grid. The same median-not-mean discipline as the Phase 1 aggregator: one
 * accident on one Tuesday should not redefine what Tuesdays look like.
 */
@Component
public class GridBuilder {

    public ForecastGrid build(Corridor corridor, List<Sample> samples, List<DayOfWeek> days,
            List<Integer> hours, boolean partial) {
        Map<Slot, List<Long>> durations = new LinkedHashMap<>();
        List<Long> distances = new ArrayList<>();

        for (Sample sample : samples) {
            durations
                    .computeIfAbsent(new Slot(sample.getDayOfWeek(), sample.getSlotHour()),
                            k -> new ArrayList<>())
                    .add((long) sample.getDurationSeconds());
            if (sample.getDistanceMeters() != null) {
                distances.add((long) sample.getDistanceMeters());
            }
        }

        Map<String, List<Bucket>> buckets = new LinkedHashMap<>();
        for (DayOfWeek day : days) {
            List<Bucket> row = new ArrayList<>(hours.size());
            for (int hour : hours) {
                List<Long> values = durations.get(new Slot(day, hour));
                // No samples yields a null median and n = 0. A zero would render as an
                // instant trip in a bright green cell, the most confidently wrong thing
                // a heatmap can do.
                row.add(new Bucket(hour, median(values),
                        values == null ? 0 : values.size()));
            }
            buckets.put(day.name(), row);
        }

        Long medianDistance = median(distances);
        return new ForecastGrid(
                corridor.getSlug(),
                corridor.getLabel(),
                corridor.getOriginCoord(),
                corridor.getDestCoord(),
                partial,
                medianDistance == null ? null : medianDistance.intValue(),
                samples.size(),
                Instant.now(),
                buckets,
                null,
                null);
    }

    static Long median(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(middle);
        }
        return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
}
