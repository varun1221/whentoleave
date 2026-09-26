package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.api.DatabaseTest;
import dev.varun.forecast.api.domain.Corridor;
import dev.varun.forecast.api.domain.Sample;
import dev.varun.forecast.api.repo.CorridorRepository;
import dev.varun.forecast.api.repo.SampleRepository;
import dev.varun.forecast.api.service.DailyIpLimiter.Budget;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The lookup path with no API key configured, so any attempt to call out fails and is
 * swallowed by the per-slot error handling. That makes these tests a precise statement
 * about which requests are served without calling TomTom at all.
 *
 * <p>Calls that do reach TomTom are covered in {@link LookupChargingTest}, against a
 * local stub.
 */
class LookupServiceTest extends DatabaseTest {

    /** Matches forecast.lookup.weekday-hours in the test configuration. */
    private static final int[] HOURS = {6, 7, 8};
    private static final DayOfWeek[] WEEKDAYS = {
        DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
    };

    @Autowired private LookupService lookups;
    @Autowired private CorridorRepository corridors;
    @Autowired private SampleRepository samples;
    @Autowired private QuotaService quotas;
    @Autowired private DailyIpLimiter perIp;
    @Autowired private QuotaDay day;

    private Corridor seeded(String slug, String origin, String dest) {
        return corridors.save(new Corridor(slug, origin, dest, slug + " label", true));
    }

    private Corridor userCorridor(String origin, String dest) {
        return corridors.save(new Corridor(null, origin, dest, null, false));
    }

    /** Fills every slot in the reduced grid with a fresh sample. */
    private void fillFreshGrid(Corridor corridor) {
        for (DayOfWeek day : WEEKDAYS) {
            for (int hour : HOURS) {
                samples.save(new Sample(corridor.getId(), day, hour, 1800, 40_000,
                        Instant.now().minus(1, ChronoUnit.DAYS)));
            }
        }
    }

    @Test
    void aWarmCorridorIsServedWithoutCallingOut() {
        Corridor corridor = userCorridor("37.1,-122.1", "38.1,-121.1");
        fillFreshGrid(corridor);

        ForecastGrid grid = lookups.lookup("37.1,-122.1", "38.1,-121.1", "10.0.0.1");

        assertEquals(15, grid.sampleCount());
        assertTrue(grid.partial(), "a user corridor is always a partial profile");
        assertNull(grid.notice(), "a full cache hit is not a degraded answer");
        assertNull(grid.resetsAt(), "and so has no limit to wait out");
        assertEquals(150, quotas.remaining(), "no quota spent");
        assertEquals(5, perIp.remaining(Budget.LOOKUP, "10.0.0.1"),
                "a cache hit must not cost the visitor a lookup");
    }

    @Test
    void aLookupOnASeededCorridorReturnsItsFullGrid() {
        Corridor corridor = seeded("sjsu-sf", "37.3352,-121.8811", "37.7946,-122.3999");
        samples.save(new Sample(corridor.getId(), DayOfWeek.SUNDAY, 18, 3600, 80_000,
                Instant.now()));

        ForecastGrid grid =
                lookups.lookup("37.3352,-121.8811", "37.7946,-122.3999", "10.0.0.2");

        assertEquals("sjsu-sf", grid.id());
        assertFalse(grid.partial(), "a seeded corridor is a full profile");
        assertEquals(7, grid.buckets().size(), "all seven days");
        assertEquals(13, grid.buckets().get("MONDAY").size(), "06:00 to 18:00");
        assertEquals(5, perIp.remaining(Budget.LOOKUP, "10.0.0.2"));
    }

    /** The cost control: near-identical coordinates must not create a second corridor. */
    @Test
    void coordinateVariantsResolveToTheSameCorridor() {
        Corridor corridor = userCorridor("37.1,-122.1", "38.1,-121.1");
        fillFreshGrid(corridor);

        lookups.lookup("  37.10000, -122.10000 ", "38.1,-121.1", "10.0.0.3");

        assertEquals(1, corridors.count(), "no duplicate corridor");
    }

    /**
     * No key configured, so nothing is sent and the lookup is refunded. Keeping the row
     * would let one visitor add a corridor per invented pair for as long as TomTom is
     * unreachable, each attempt free. Registration when calls were sent is covered in
     * {@link LookupChargingTest}.
     */
    @Test
    void anUnknownPairThatSendsNothingLeavesNoCorridor() {
        ForecastGrid grid = lookups.lookup("37.9,-122.9", "38.9,-121.9", "10.0.0.4");

        assertEquals(0, grid.sampleCount());
        assertEquals(0, corridors.count(), "nothing fetched, so no row written");
        assertEquals(150, quotas.remaining());
        assertEquals(5, perIp.remaining(Budget.LOOKUP, "10.0.0.4"));
    }

    /**
     * A corridor row is a write anyone can cause by inventing coordinates, so one is only
     * made for a visitor who has cleared every guard. Otherwise a refused visitor could
     * still grow the table by one row per request, without limit.
     */
    @Test
    void aRateLimitedVisitorRegistersNoCorridor() {
        String ip = "10.0.0.19";
        for (int i = 0; i < 5; i++) {
            perIp.tryConsume(Budget.LOOKUP, ip);
        }

        for (int i = 0; i < 3; i++) {
            String origin = "37.2" + i + ",-122.2";
            assertThrows(RateLimitedException.class,
                    () -> lookups.lookup(origin, "38.2,-121.2", ip));
        }

        assertEquals(0, corridors.count());
    }

    @Test
    void aPausedServiceRegistersNoCorridor() {
        jdbc.update("UPDATE service_setting SET value = 'false' "
                + "WHERE key = 'lookups_enabled'");

        assertThrows(LookupsUnavailableException.class,
                () -> lookups.lookup("37.31,-122.31", "38.31,-121.31", "10.0.0.20"));

        assertEquals(0, corridors.count());
    }

    @Test
    void aSpentGlobalBudgetRegistersNoCorridor() {
        quotas.reserve(150);

        assertThrows(LookupsUnavailableException.class,
                () -> lookups.lookup("37.32,-122.32", "38.32,-121.32", "10.0.0.21"));

        assertEquals(0, corridors.count());
    }

    @Test
    void rejectsAnIdenticalOriginAndDestination() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> lookups.lookup("37.1,-122.1", "37.10000,-122.1", "10.0.0.5"));

        assertTrue(thrown.getMessage().contains("same place"));
    }

    @Test
    void rejectsAMalformedCoordinate() {
        assertThrows(IllegalArgumentException.class,
                () -> lookups.lookup("nowhere", "38.1,-121.1", "10.0.0.6"));
    }

    @Test
    void staleSamplesDoNotCountAsCached() {
        Corridor corridor = userCorridor("37.2,-122.2", "38.2,-121.2");
        // Older than the 7-day TTL.
        samples.save(new Sample(corridor.getId(), DayOfWeek.MONDAY, 6, 1800, 40_000,
                Instant.now().minus(30, ChronoUnit.DAYS)));

        ForecastGrid grid = lookups.lookup("37.2,-122.2", "38.2,-121.2", "10.0.0.7");

        assertEquals(0, grid.sampleCount(), "a stale sample is not served");
    }

    @Test
    void whenPausedAPartiallyCachedCorridorIsStillServedWithANotice() {
        Corridor corridor = userCorridor("37.3,-122.3", "38.3,-121.3");
        samples.save(new Sample(corridor.getId(), DayOfWeek.MONDAY, 6, 1800, 40_000,
                Instant.now()));
        jdbc.update("UPDATE service_setting SET value = 'false' "
                + "WHERE key = 'lookups_enabled'");

        ForecastGrid grid = lookups.lookup("37.3,-122.3", "38.3,-121.3", "10.0.0.8");

        assertEquals(ApiCode.LOOKUPS_PAUSED, grid.notice());
        assertEquals(1, grid.sampleCount());
        assertEquals(150, quotas.remaining(), "the switch is checked before spending");
        assertNull(grid.resetsAt(),
                "the switch lifts when a human lifts it, not at midnight");
    }

    @Test
    void whenPausedAnUncachedCorridorReportsThatDistinctly() {
        jdbc.update("UPDATE service_setting SET value = 'false' "
                + "WHERE key = 'lookups_enabled'");

        LookupsUnavailableException thrown = assertThrows(LookupsUnavailableException.class,
                () -> lookups.lookup("37.4,-122.4", "38.4,-121.4", "10.0.0.9"));

        assertEquals(ApiCode.LOOKUPS_PAUSED, thrown.code());
    }

    @Test
    void whenTheBudgetIsSpentAnUncachedCorridorReportsThatDistinctly() {
        quotas.reserve(150);

        LookupsUnavailableException thrown = assertThrows(LookupsUnavailableException.class,
                () -> lookups.lookup("37.5,-122.5", "38.5,-121.5", "10.0.0.10"));

        assertEquals(ApiCode.QUOTA_EXHAUSTED, thrown.code());
        assertEquals(0, thrown.remaining());
    }

    /** A global limit must not cost the visitor one of their five. */
    @Test
    void aGlobalRefusalDoesNotSpendTheVisitorsBudget() {
        quotas.reserve(150);

        assertThrows(LookupsUnavailableException.class,
                () -> lookups.lookup("37.6,-122.6", "38.6,-121.6", "10.0.0.11"));

        assertEquals(5, perIp.remaining(Budget.LOOKUP, "10.0.0.11"));
    }

    /**
     * §9.4: "Only actual TomTom calls do [count]". With no key configured nothing is
     * sent, so neither the shared budget nor the visitor's lookups may be spent.
     */
    @Test
    void aLookupThatNeverReachesTomTomCostsNothing() {
        lookups.lookup("37.12,-122.12", "38.12,-121.12", "10.0.0.15");

        assertEquals(150, quotas.remaining(), "unsent calls go back to the shared budget");
        assertEquals(5, perIp.remaining(Budget.LOOKUP, "10.0.0.15"),
                "and the visitor gets their lookup back");
    }

    @Test
    void aRateLimitedRefusalSaysWhenTheBudgetRefills() {
        String ip = "10.0.0.16";
        for (int i = 0; i < 5; i++) {
            perIp.tryConsume(Budget.LOOKUP, ip);
        }

        RateLimitedException thrown = assertThrows(RateLimitedException.class,
                () -> lookups.lookup("37.13,-122.13", "38.13,-121.13", ip));

        assertEquals(day.resetsAt(), thrown.resetsAt());
    }

    @Test
    void anExhaustedVisitorIsRefusedOnAnUncachedCorridor() {
        String ip = "10.0.0.12";
        for (int i = 0; i < 5; i++) {
            perIp.tryConsume(Budget.LOOKUP, ip);
        }

        RateLimitedException thrown = assertThrows(RateLimitedException.class,
                () -> lookups.lookup("37.7,-122.7", "38.7,-121.7", ip));

        assertEquals(5, thrown.dailyLimit());
        assertEquals(150, quotas.remaining(), "and spends none of the shared budget");
    }

    @Test
    void anExhaustedVisitorStillSeesPartiallyCachedData() {
        String ip = "10.0.0.13";
        Corridor corridor = userCorridor("37.8,-122.8", "38.8,-121.8");
        samples.save(new Sample(corridor.getId(), DayOfWeek.MONDAY, 6, 1800, 40_000,
                Instant.now()));
        for (int i = 0; i < 5; i++) {
            perIp.tryConsume(Budget.LOOKUP, ip);
        }

        ForecastGrid grid = lookups.lookup("37.8,-122.8", "38.8,-121.8", ip);

        assertEquals(ApiCode.RATE_LIMITED, grid.notice());
        assertEquals(1, grid.sampleCount());
    }

    /**
     * §10.4: the banner says when the limit lifts. A degraded 200 carries the same
     * reset time the 429 for the same limit would have, so a visitor with one cached
     * bucket is not told less than a visitor with none.
     */
    @Test
    void aRateLimitedGridSaysWhenTheVisitorsBudgetRefills() {
        String ip = "10.0.0.17";
        Corridor corridor = userCorridor("37.14,-122.14", "38.14,-121.14");
        samples.save(new Sample(corridor.getId(), DayOfWeek.MONDAY, 6, 1800, 40_000,
                Instant.now()));
        for (int i = 0; i < 5; i++) {
            perIp.tryConsume(Budget.LOOKUP, ip);
        }

        ForecastGrid grid = lookups.lookup("37.14,-122.14", "38.14,-121.14", ip);

        assertEquals(ApiCode.RATE_LIMITED, grid.notice());
        assertEquals(day.resetsAt(), grid.resetsAt());
    }

    @Test
    void aQuotaExhaustedGridSaysWhenTheSharedBudgetRefills() {
        Corridor corridor = userCorridor("37.15,-122.15", "38.15,-121.15");
        samples.save(new Sample(corridor.getId(), DayOfWeek.MONDAY, 6, 1800, 40_000,
                Instant.now()));
        quotas.reserve(150);

        ForecastGrid grid = lookups.lookup("37.15,-122.15", "38.15,-121.15", "10.0.0.18");

        assertEquals(ApiCode.QUOTA_EXHAUSTED, grid.notice());
        assertEquals(day.resetsAt(), grid.resetsAt());
        assertEquals(5, perIp.remaining(Budget.LOOKUP, "10.0.0.18"),
                "a global limit does not cost the visitor a lookup");
    }

    @Test
    void theGridShapeMatchesTheConfiguredReducedProfile() {
        Corridor corridor = userCorridor("37.11,-122.11", "38.11,-121.11");
        fillFreshGrid(corridor);

        ForecastGrid grid = lookups.lookup("37.11,-122.11", "38.11,-121.11", "10.0.0.14");

        assertEquals(5, grid.buckets().size(), "weekdays only");
        assertFalse(grid.buckets().containsKey("SATURDAY"));
        assertFalse(grid.buckets().containsKey("SUNDAY"));
        assertEquals(3, grid.buckets().get("MONDAY").size());
        assertNotNull(grid.generatedAt());
    }
}
