package dev.varun.forecast.api.service;

import dev.varun.forecast.api.client.CallBudget;
import dev.varun.forecast.api.client.CallNotSentException;
import dev.varun.forecast.api.client.RoutingClient;
import dev.varun.forecast.api.config.ForecastProperties;
import dev.varun.forecast.api.domain.Corridor;
import dev.varun.forecast.api.domain.Sample;
import dev.varun.forecast.api.repo.CorridorRepository;
import dev.varun.forecast.api.repo.SampleRepository;
import dev.varun.forecast.api.service.DailyIpLimiter.Budget;
import dev.varun.forecast.api.service.DailyIpLimiter.Spend;
import java.io.IOException;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
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
    private final DailyIpLimiter perIp;
    private final KillSwitch killSwitch;
    private final QuotaDay day;
    private final ForecastProperties props;
    private final Clock clock;

    public LookupService(CorridorRepository corridors, SampleRepository samples,
            RoutingClient routing, QuotaService quotas, GridBuilder grids,
            ForecastService seeded, DailyIpLimiter perIp, KillSwitch killSwitch,
            QuotaDay day, ForecastProperties props, Clock clock) {
        this.corridors = corridors;
        this.samples = samples;
        this.routing = routing;
        this.quotas = quotas;
        this.grids = grids;
        this.seeded = seeded;
        this.perIp = perIp;
        this.killSwitch = killSwitch;
        this.day = day;
        this.props = props;
        this.clock = clock;
    }

    public ForecastGrid lookup(String rawOrigin, String rawDest, String clientIp) {
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
        Instant cutoff = clock.instant().minus(props.cache().ttlDays(), ChronoUnit.DAYS);

        List<Sample> fresh =
                samples.findByCorridorIdAndRequestedAtAfter(corridor.getId(), cutoff);
        List<Slot> missing = missingSlots(fresh, hours);

        if (missing.isEmpty()) {
            // The whole point of the design: a warm corridor is free, and costs the
            // visitor none of their five daily lookups.
            log.info("lookup cache hit corridor={} samples={}", corridor.getId(),
                    fresh.size());
            return grids.build(corridor, fresh, WEEKDAYS, hours, true);
        }

        // Everything below this line can spend money, so the three guards sit here and
        // not in a servlet filter: a filter would charge cache hits too.
        if (!killSwitch.lookupsEnabled()) {
            return degraded(corridor, fresh, hours, ApiCode.LOOKUPS_PAUSED,
                    () -> LookupsUnavailableException.lookupsPaused(quotas.remaining()));
        }

        // The visitor's lookup is spent first, as one atomic check-and-spend: a separate
        // check would let parallel requests from one IP all pass it. A rate-limited
        // visitor therefore never touches the shared budget.
        Optional<Spend> spend = perIp.tryConsume(Budget.LOOKUP, clientIp);
        if (spend.isEmpty()) {
            return degraded(corridor, fresh, hours, ApiCode.RATE_LIMITED,
                    () -> RateLimitedException.used("lookups",
                            perIp.dailyLimit(Budget.LOOKUP), day.resetsAt()));
        }

        QuotaService.Reservation reservation = quotas.reserve(missing.size());
        int granted = reservation.granted();
        if (granted == 0) {
            // The visitor should not be charged for a global limit.
            perIp.refund(spend.get());
            return degraded(corridor, fresh, hours, ApiCode.QUOTA_EXHAUSTED,
                    () -> LookupsUnavailableException.budgetSpent(quotas.remaining(),
                            day.resetsAt()));
        }

        QuotaBudget budget = new QuotaBudget(quotas, reservation);
        List<Sample> saved;
        try {
            saved = fill(corridor, missing.subList(0, granted), budget);
        } finally {
            // §9.4: only calls that actually reached TomTom count, against either limit.
            // In a finally because an unexpected failure mid-fill is exactly when the
            // reservations it never used would otherwise be stranded.
            quotas.release(reservation, budget.unspent());
            if (budget.spent() == 0) {
                perIp.refund(spend.get());
            }
        }

        List<Sample> combined = new ArrayList<>(fresh);
        combined.addAll(saved);
        log.info("lookup corridor={} cached={} sent={} fetched={} stillMissing={}",
                corridor.getId(), fresh.size(), budget.spent(), saved.size(),
                missing.size() - saved.size());
        return grids.build(corridor, combined, WEEKDAYS, hours, true);
    }

    /**
     * Serve what is cached, labelled with why it is not more. Only when there is nothing
     * cached at all does a limit become an error the visitor sees.
     */
    private ForecastGrid degraded(Corridor corridor, List<Sample> fresh,
            List<Integer> hours, ApiCode notice,
            Supplier<RuntimeException> ifEmpty) {
        if (fresh.isEmpty()) {
            throw ifEmpty.get();
        }
        log.info("serving {} cached samples for corridor={} ({})", fresh.size(),
                corridor.getId(), notice);
        return grids.build(corridor, fresh, WEEKDAYS, hours, true).withNotice(notice);
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
     * Fetches and persists the given slots, spending {@code budget} as it goes.
     *
     * <p>A failure on one slot does not abandon the rest — the same partial-failure
     * tolerance the sampler has, for the same reason: a grid with a hole still renders.
     *
     * <p>Nothing here counts calls. The budget is charged at the moment a request goes
     * out, which is the only place that knows whether one did: counting per slot instead
     * missed retries, and counting after the fact charged nothing for a request
     * interrupted between sending and reading its answer.
     */
    @Transactional
    List<Sample> fill(Corridor corridor, List<Slot> slots, CallBudget budget) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(DepartureSlots.ZONE));
        List<Sample> saved = new ArrayList<>();
        for (Slot slot : slots) {
            try {
                ZonedDateTime departAt =
                        DepartureSlots.nextOccurrence(slot.day(), slot.hour(), now);
                Optional<RoutingClient.RouteResult> result = routing.compute(
                        corridor.getOriginCoord(), corridor.getDestCoord(), departAt,
                        budget);
                if (result.isEmpty()) {
                    continue;
                }
                saved.add(samples.save(new Sample(
                        corridor.getId(),
                        slot.day(),
                        slot.hour(),
                        (int) result.get().durationSeconds(),
                        result.get().distanceMeters(),
                        clock.instant())));
            } catch (CallNotSentException e) {
                log.warn("lookup slot {} {}:00 not sent: {}", slot.day(), slot.hour(),
                        e.getMessage());
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
