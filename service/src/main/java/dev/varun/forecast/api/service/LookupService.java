package dev.varun.forecast.api.service;

import dev.varun.forecast.api.client.RoutingClient;
import dev.varun.forecast.api.config.ForecastProperties;
import dev.varun.forecast.api.domain.Corridor;
import dev.varun.forecast.api.domain.Sample;
import dev.varun.forecast.api.repo.CorridorRepository;
import dev.varun.forecast.api.repo.SampleRepository;
import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cache-first lookup for an arbitrary corridor.
 *
 * <p>The {@code sample} table <em>is</em> the cache. Any bucket younger than the TTL is
 * served from Postgres, so two visitors asking about the same corridor cost one set of
 * API calls between them. Adding Redis here would be a third cache tier serving nothing.
 */
@Service
public class LookupService {

    private static final Logger log = LoggerFactory.getLogger(LookupService.class);

    /** Weekdays only for user lookups — a Sunday grid is flat and not worth 13 calls. */
    private static final List<DayOfWeek> WEEKDAYS = List.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);

    private final CorridorRepository corridors;
    private final SampleRepository samples;
    private final RoutingClient routing;
    private final QuotaService quotas;
    private final GridBuilder grids;
    private final ForecastService seeded;
    private final ForecastProperties props;

    public LookupService(CorridorRepository corridors, SampleRepository samples,
            RoutingClient routing, QuotaService quotas, GridBuilder grids,
            ForecastService seeded, ForecastProperties props) {
        this.corridors = corridors;
        this.samples = samples;
        this.routing = routing;
        this.quotas = quotas;
        this.grids = grids;
        this.seeded = seeded;
        this.props = props;
    }

    public ForecastGrid lookup(String rawOrigin, String rawDest) {
        String origin = Coordinates.normalise(rawOrigin);
        String dest = Coordinates.normalise(rawDest);
        if (origin.equals(dest)) {
            throw new IllegalArgumentException("Origin and destination are the same place");
        }

        Corridor corridor = resolveCorridor(origin, dest);
        if (corridor.isSeeded()) {
            // Already swept weekly at full resolution. Returning the reduced grid here
            // would give a visitor a worse answer than clicking the same corridor in
            // the list, and would cost calls to rebuild what is already on disk.
            return seeded.forecastFor(corridor.getSlug()).orElseThrow();
        }
        List<Integer> hours = props.lookup().weekdayHours();
        Instant cutoff = Instant.now().minus(props.cache().ttlDays(), ChronoUnit.DAYS);

        List<Sample> fresh =
                samples.findByCorridorIdAndRequestedAtAfter(corridor.getId(), cutoff);
        List<Slot> missing = missingSlots(fresh, hours);

        if (missing.isEmpty()) {
            // The whole point of the design: a warm corridor is free.
            log.info("lookup cache hit corridor={} samples={}", corridor.getId(),
                    fresh.size());
            return grids.build(corridor, fresh, WEEKDAYS, hours, true);
        }

        int granted = quotas.reserve(missing.size());
        if (granted == 0) {
            if (fresh.isEmpty()) {
                throw new QuotaExhaustedException(
                        "Live lookups are paused until tomorrow", quotas.remaining());
            }
            // Partial data beats an error. The grid is labelled partial either way.
            log.info("quota exhausted, serving {} cached samples for corridor={}",
                    fresh.size(), corridor.getId());
            return grids.build(corridor, fresh, WEEKDAYS, hours, true);
        }

        List<Sample> added = fill(corridor, missing.subList(0, granted));
        List<Sample> combined = new ArrayList<>(fresh);
        combined.addAll(added);
        log.info("lookup corridor={} cached={} fetched={} stillMissing={}",
                corridor.getId(), fresh.size(), added.size(),
                missing.size() - added.size());
        return grids.build(corridor, combined, WEEKDAYS, hours, true);
    }

    @Transactional
    Corridor resolveCorridor(String origin, String dest) {
        return corridors.findByOriginCoordAndDestCoord(origin, dest)
                .orElseGet(() -> corridors.save(
                        new Corridor(null, origin, dest, null, false)));
    }

    /** Slots in the target grid with no fresh sample behind them. */
    private static List<Slot> missingSlots(List<Sample> fresh, List<Integer> hours) {
        Set<Slot> have = new HashSet<>();
        for (Sample sample : fresh) {
            have.add(new Slot(sample.getDayOfWeek(), sample.getSlotHour()));
        }
        List<Slot> missing = new ArrayList<>();
        for (DayOfWeek day : WEEKDAYS) {
            for (int hour : hours) {
                Slot slot = new Slot(day, hour);
                if (!have.contains(slot)) {
                    missing.add(slot);
                }
            }
        }
        return missing;
    }

    /**
     * Fetches and persists the given slots.
     *
     * <p>A failure on one slot does not abandon the rest — the same partial-failure
     * tolerance the sampler has, for the same reason: a grid with a hole still renders.
     */
    @Transactional
    List<Sample> fill(Corridor corridor, List<Slot> slots) {
        ZonedDateTime now = ZonedDateTime.now(DepartureSlots.ZONE);
        List<Sample> saved = new ArrayList<>();
        for (Slot slot : slots) {
            try {
                ZonedDateTime departAt =
                        DepartureSlots.nextOccurrence(slot.day(), slot.hour(), now);
                Optional<RoutingClient.RouteResult> result = routing.compute(
                        corridor.getOriginCoord(), corridor.getDestCoord(), departAt);
                if (result.isEmpty()) {
                    continue;
                }
                saved.add(samples.save(new Sample(
                        corridor.getId(),
                        slot.day(),
                        slot.hour(),
                        (int) result.get().durationSeconds(),
                        result.get().distanceMeters(),
                        Instant.now())));
            } catch (IOException e) {
                log.warn("lookup slot {} {}:00 failed: {}", slot.day(), slot.hour(),
                        e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return saved;
    }
}
