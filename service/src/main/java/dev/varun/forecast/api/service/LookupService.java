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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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

        // Found, not created: a corridor row is only written once a visitor has cleared
        // every guard below. Creating it up front let anyone refused by those guards
        // still grow the table by one row per invented coordinate pair.
        Optional<Corridor> known = corridors.findByOriginCoordAndDestCoord(origin, dest);
        if (known.isPresent() && known.get().isSeeded()) {
            // Already swept weekly at full resolution. Returning the reduced grid here
            // would give a visitor a worse answer than clicking the same corridor in
            // the list, and would cost calls to rebuild what is already on disk.
            return seeded.forecastFor(known.get().getSlug()).orElseThrow();
        }
        List<Integer> hours = props.lookup().weekdayHours();
        Instant cutoff = clock.instant().minus(props.cache().ttlDays(), ChronoUnit.DAYS);

        List<Sample> fresh = known
                .map(c -> samples.findByCorridorIdAndRequestedAtAfter(c.getId(), cutoff))
                .orElse(List.of());
        List<Slot> missing = missingSlots(fresh, hours);

        if (missing.isEmpty()) {
            // The whole point of the design: a warm corridor is free, and costs the
            // visitor none of their five daily lookups.
            Corridor corridor = known.orElseThrow();
            log.info("lookup cache hit corridor={} samples={}", corridor.getId(),
                    fresh.size());
            return lookupGrid(corridor, fresh);
        }

        // Everything below this line can spend money, so the three guards sit here and
        // not in a servlet filter: a filter would charge cache hits too.
        if (!killSwitch.lookupsEnabled()) {
            // No reset time, here or in the 429: the switch ends when a human ends it,
            // and a promised midnight would be a promise nothing keeps.
            return degraded(known, fresh, ApiCode.LOOKUPS_PAUSED, null,
                    () -> LookupsUnavailableException.lookupsPaused(quotas.remaining()));
        }

        // The visitor's lookup is spent first, as one atomic check-and-spend: a separate
        // check would let parallel requests from one IP all pass it. A rate-limited
        // visitor therefore never touches the shared budget.
        Optional<Spend> spend = perIp.tryConsume(Budget.LOOKUP, clientIp);
        if (spend.isEmpty()) {
            return degraded(known, fresh, ApiCode.RATE_LIMITED, day.resetsAt(),
                    () -> RateLimitedException.used("lookups",
                            perIp.dailyLimit(Budget.LOOKUP), day.resetsAt()));
        }

        QuotaService.Reservation reservation = quotas.reserve(missing.size());
        int granted = reservation.granted();
        if (granted == 0) {
            // The visitor should not be charged for a global limit.
            perIp.refund(spend.get());
            return degraded(known, fresh, ApiCode.QUOTA_EXHAUSTED,
                    day.resetsAt(),
                    () -> LookupsUnavailableException.budgetSpent(quotas.remaining(),
                            day.resetsAt()));
        }

        QuotaBudget budget = new QuotaBudget(quotas, reservation);
        // A new pair stays unsaved until fill has a sample to attach to it. Written up
        // front, a lookup that reached nothing would leave the row behind, refunded, so
        // while TomTom was unreachable one visitor could add one per invented pair for
        // free; and deleting it afterwards could pull it from under a second request
        // filling the same pair.
        Corridor corridor = known.orElseGet(() -> new Corridor(null, origin, dest, null,
                false));
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
                saved.isEmpty() ? corridor.getId() : saved.get(0).getCorridorId(),
                fresh.size(), budget.spent(), saved.size(),
                missing.size() - saved.size());
        return lookupGrid(corridor, combined);
    }

    /** A user corridor's grid: weekday peaks only, and always labelled partial. */
    private ForecastGrid lookupGrid(Corridor corridor, List<Sample> samples) {
        return grids.build(corridor, samples, WEEKDAYS, props.lookup().weekdayHours(),
                true);
    }

    /**
     * Serve what is cached, labelled with why it is not more. Only when there is nothing
     * cached at all does a limit become an error the visitor sees.
     *
     * <p>The notice and the exception carry the same reason and the same reset time, so
     * how much of the corridor happened to be cached changes how much the visitor is
     * shown, never what they are told about the limit.
     *
     * @param known the corridor, which is always present when anything is cached
     * @param resetsAt when the limit lifts, or null for one with no scheduled end
     */
    private ForecastGrid degraded(Optional<Corridor> known, List<Sample> fresh,
            ApiCode notice, Instant resetsAt,
            Supplier<RuntimeException> ifEmpty) {
        if (fresh.isEmpty()) {
            throw ifEmpty.get();
        }
        Corridor corridor = known.orElseThrow();
        log.info("serving {} cached samples for corridor={} ({})", fresh.size(),
                corridor.getId(), notice);
        return lookupGrid(corridor, fresh).withNotice(notice, resetsAt);
    }

    /**
     * Saves a new pair's row, or finds the one a concurrent lookup for the same pair
     * saved first. Nothing deletes a user corridor, so the find cannot miss.
     */
    private Long register(Corridor pair) {
        corridors.insertIfAbsent(pair.getOriginCoord(), pair.getDestCoord());
        return corridors.findByOriginCoordAndDestCoord(pair.getOriginCoord(),
                pair.getDestCoord()).orElseThrow().getId();
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
     * <p>The calls go out together, one thread each, and {@link RoutingClient} spaces
     * them to the rate TomTom allows: about eleven seconds for a cold corridor's 45,
     * where waiting for each answer before sending the next took twice that. Samples
     * are saved here, on the calling thread, in slot order as the answers arrive.
     *
     * <p>A failure on one slot does not abandon the rest — the same partial-failure
     * tolerance the sampler has, for the same reason: a grid with a hole still renders.
     *
     * <p>Nothing here counts calls. The budget is charged at the moment a request goes
     * out, which is the only place that knows whether one did: counting per slot instead
     * missed retries, and counting after the fact charged nothing for a request
     * interrupted between sending and reading its answer.
     *
     * <p>Every call has finished before this returns, however it returns. The caller
     * releases whatever the budget has left, and a call still running could spend after
     * that release and so go out uncounted.
     */
    List<Sample> fill(Corridor corridor, List<Slot> slots, QuotaBudget budget) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(DepartureSlots.ZONE));
        List<Sample> saved = new ArrayList<>();
        Long corridorId = corridor.getId();
        // Virtual threads, because each call spends nearly all its time waiting: for its
        // turn under the rate limit, then for TomTom.
        try (ExecutorService calls = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Optional<RoutingClient.RouteResult>>> results = new ArrayList<>();
            for (Slot slot : slots) {
                CallBudget perCall = budget.forCall();
                results.add(calls.submit(() -> fetch(corridor, slot, now, perCall)));
            }
            try {
                for (int i = 0; i < slots.size(); i++) {
                    Optional<RoutingClient.RouteResult> result = results.get(i).get();
                    if (result.isEmpty()) {
                        continue;
                    }
                    if (corridorId == null) {
                        corridorId = register(corridor);
                    }
                    Slot slot = slots.get(i);
                    saved.add(samples.save(new Sample(
                            corridorId,
                            slot.day(),
                            slot.hour(),
                            (int) result.get().durationSeconds(),
                            result.get().distanceMeters(),
                            clock.instant())));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                // fetch handles every failure it expects, so this one is not expected.
                switch (e.getCause()) {
                    case InterruptedException interrupt ->
                            Thread.currentThread().interrupt();
                    case RuntimeException unchecked -> throw unchecked;
                    case Error error -> throw error;
                    default -> throw new IllegalStateException(e.getCause());
                }
            } finally {
                // A no-op once every call has answered. Otherwise it stops those still
                // waiting for their turn, before any of them is charged, and closing the
                // executor then waits out the ones already sent.
                calls.shutdownNow();
            }
        }
        return saved;
    }

    /** One slot's call. Empty when it found no route or could not be completed. */
    private Optional<RoutingClient.RouteResult> fetch(Corridor corridor, Slot slot,
            ZonedDateTime now, CallBudget budget) throws InterruptedException {
        try {
            ZonedDateTime departAt =
                    DepartureSlots.nextOccurrence(slot.day(), slot.hour(), now);
            return routing.compute(corridor.getOriginCoord(), corridor.getDestCoord(),
                    departAt, budget);
        } catch (CallNotSentException e) {
            log.warn("lookup slot {} {}:00 not sent: {}", slot.day(), slot.hour(),
                    e.getMessage());
        } catch (IOException e) {
            log.warn("lookup slot {} {}:00 failed: {}", slot.day(), slot.hour(),
                    e.getMessage());
        }
        return Optional.empty();
    }
}
