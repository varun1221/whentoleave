package dev.varun.forecast;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.varun.forecast.model.Bucket;
import dev.varun.forecast.model.ForecastsFile;
import dev.varun.forecast.model.Route;
import dev.varun.forecast.model.RouteForecast;
import dev.varun.forecast.model.Sample;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/** Collapses the JSONL history into the single forecasts.json the site fetches. */
public final class Aggregator {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Median, not mean — one accident shouldn't redefine a corridor's Tuesday. */
    static Long median(List<Long> values) {
        if (values.isEmpty()) {
            return null;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int mid = sorted.size() / 2;
        if (sorted.size() % 2 == 1) {
            return sorted.get(mid);
        }
        return Math.round((sorted.get(mid - 1) + sorted.get(mid)) / 2.0);
    }

    /**
     * Buckets for one route: every day/hour cell of the grid, present whether or not it
     * has data. An empty cell carries a null median and n = 0, never a zero duration.
     */
    static Map<String, List<Bucket>> bucket(List<Sample> samples) {
        Map<String, Map<Integer, List<Long>>> grouped = new LinkedHashMap<>();
        for (DayOfWeek day : DayOfWeek.values()) {
            grouped.put(day.name(), new LinkedHashMap<>());
        }
        for (Sample sample : samples) {
            Map<Integer, List<Long>> byHour = grouped.get(sample.dayOfWeek());
            if (byHour == null) {
                continue; // unrecognised day label; skip rather than crash the build
            }
            byHour.computeIfAbsent(sample.slotHour(), h -> new ArrayList<>())
                    .add(sample.durationSeconds());
        }

        Map<String, List<Bucket>> buckets = new LinkedHashMap<>();
        for (Map.Entry<String, Map<Integer, List<Long>>> entry : grouped.entrySet()) {
            List<Bucket> row = new ArrayList<>(DepartureSchedule.HOURS_PER_DAY);
            for (int hour = DepartureSchedule.START_HOUR;
                    hour <= DepartureSchedule.END_HOUR;
                    hour++) {
                List<Long> durations =
                        entry.getValue().getOrDefault(hour, List.of());
                row.add(new Bucket(hour, median(durations), durations.size()));
            }
            buckets.put(entry.getKey(), row);
        }
        return buckets;
    }

    /**
     * Distinct dates the samples were requested on — one per sweep.
     *
     * <p>Counting ISO weeks instead would under-report: a manual run on Saturday and
     * the Sunday cron both land in one ISO week, so three sweeps would read as two
     * while every cell reported n = 3. The two numbers appear side by side in the
     * footer, so they have to agree.
     */
    static int sweepsSampled(List<Sample> samples) {
        Set<LocalDate> dates = new HashSet<>();
        for (Sample sample : samples) {
            if (sample.requestedAt() == null) {
                continue;
            }
            dates.add(Instant.parse(sample.requestedAt())
                    .atZone(ZoneOffset.UTC).toLocalDate());
        }
        return dates.size();
    }

    public ForecastsFile build(List<Route> routes, SampleStore store) throws IOException {
        List<RouteForecast> forecasts = new ArrayList<>();
        List<Sample> everything = new ArrayList<>();

        for (Route route : routes) {
            List<Sample> samples = store.readAll(route.id());
            everything.addAll(samples);
            List<Long> distances = samples.stream()
                    .map(Sample::distanceMeters)
                    .filter(d -> d != null)
                    .toList();
            forecasts.add(new RouteForecast(
                    route.id(), route.name(), median(distances), bucket(samples)));
        }

        return new ForecastsFile(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(
                        java.time.temporal.ChronoUnit.SECONDS)),
                sweepsSampled(everything),
                everything.size(),
                forecasts);
    }

    public void writeTo(Path out, ForecastsFile forecasts) throws IOException {
        Files.createDirectories(out.getParent());
        Files.writeString(
                out,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(forecasts),
                StandardCharsets.UTF_8);
    }
}
