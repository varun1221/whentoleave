package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.api.DatabaseTest;
import dev.varun.forecast.api.client.SearchClient;
import dev.varun.forecast.api.client.StubTomTom;
import dev.varun.forecast.api.config.ForecastProperties;
import dev.varun.forecast.api.repo.IpUsageRepository;
import dev.varun.forecast.api.service.DailyIpLimiter.Budget;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Autocomplete is the most expensive request shape in the app: it fires as someone
 * types, so one address can generate a dozen requests. These tests are about the three
 * guards that stop that from being billable.
 */
@ExtendWith(OutputCaptureExtension.class)
class PlacesServiceTest extends DatabaseTest {

    private static final String IP = "1.2.3.4";
    private static final String BODY = """
            {"results":[{"position":{"lat":37.4419,"lon":-122.1430},
             "address":{"freeformAddress":"Palo Alto, CA"}}]}
            """;

    @Autowired
    private IpUsageRepository usage;

    private record Fixture(PlacesService places, DailyIpLimiter limiter) {}

    private Fixture fixture(StubTomTom stub, int searchPerIp, int minLength) {
        return fixtureAt(stub.baseUrl(), searchPerIp, minLength);
    }

    private Fixture fixtureAt(String baseUrl, int searchPerIp, int minLength) {
        ForecastProperties props =
                TestProps.with(baseUrl, "test-key", 5, searchPerIp, minLength);
        QuotaDay day = new QuotaDay(Clock.systemUTC());
        DailyIpLimiter limiter =
                new DailyIpLimiter(usage, props, day, new IpHasher(props));
        return new Fixture(
                new PlacesService(new SearchClient(props), limiter, day, props), limiter);
    }

    /** Not an error: the caller is still typing, and an empty list costs nothing. */
    @Test
    void aQueryUnderTheFloorNeverReachesTomTom() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, BODY)) {
            Fixture f = fixture(stub, 20, 3);

            assertTrue(f.places().suggest("sj", IP).isEmpty());
            assertEquals(0, stub.requestCount(), "must not call out for a 2-char query");
            assertEquals(20, f.limiter().remaining(Budget.SEARCH, IP),
                    "and must not spend a token");
        }
    }

    @Test
    void aRealQueryCallsOutOnceAndSpendsOneToken() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, BODY)) {
            Fixture f = fixture(stub, 20, 3);

            assertEquals(1, f.places().suggest("palo alto", IP).size());
            assertEquals(1, stub.requestCount());
            assertEquals(19, f.limiter().remaining(Budget.SEARCH, IP));
        }
    }

    @Test
    void repeatingAQueryIsServedFromCacheAndSpendsNothing() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, BODY)) {
            Fixture f = fixture(stub, 20, 3);

            f.places().suggest("palo alto", IP);
            f.places().suggest("palo alto", IP);
            f.places().suggest("palo alto", IP);

            assertEquals(1, stub.requestCount(), "cache must absorb the repeats");
            assertEquals(19, f.limiter().remaining(Budget.SEARCH, IP));
        }
    }

    /** Case and spacing do not change the answer, so they must not cost a request. */
    @Test
    void caseAndWhitespaceHitTheSameCacheEntry() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, BODY)) {
            Fixture f = fixture(stub, 20, 3);

            f.places().suggest("palo alto", IP);
            f.places().suggest("  PALO   alto ", IP);
            f.places().suggest("Palo Alto", IP);

            assertEquals(1, stub.requestCount());
        }
    }

    @Test
    void refusesANewQueryOnceTheDailyBudgetIsGone() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, BODY).enqueue(200, BODY)) {
            Fixture f = fixture(stub, 1, 3);
            f.places().suggest("palo alto", IP);

            RateLimitedException thrown = assertThrows(RateLimitedException.class,
                    () -> f.places().suggest("berkeley bart", IP));
            assertEquals(1, thrown.dailyLimit());
            assertEquals(1, stub.requestCount(), "the refused query must not call out");
        }
    }

    /** A cached answer costs nothing to serve, so a spent budget should not block it. */
    @Test
    void stillServesACachedQueryWhileRateLimited() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, BODY)) {
            Fixture f = fixture(stub, 1, 3);
            f.places().suggest("palo alto", IP);

            assertEquals(1, f.places().suggest("palo alto", IP).size());
            assertEquals(1, stub.requestCount());
        }
    }

    @Test
    void oneVisitorExhaustingTheirBudgetDoesNotBlockAnother() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, BODY).enqueue(200, BODY)) {
            Fixture f = fixture(stub, 1, 3);
            f.places().suggest("palo alto", IP);

            assertEquals(1, f.places().suggest("berkeley bart", "9.9.9.9").size());
        }
    }

    /**
     * Per-IP budgets alone do not bound search: a caller with many addresses gets many
     * budgets. The shared ceiling is what caps what TomTom sees in a day, whoever asks.
     */
    @Test
    void theSharedDailyCeilingStopsSearchWhateverTheAddress() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, BODY).enqueue(200, BODY)) {
            ForecastProperties props = TestProps.searchCeiling(stub.baseUrl(), 20, 2);
            QuotaDay day = new QuotaDay(Clock.systemUTC());
            DailyIpLimiter limiter =
                    new DailyIpLimiter(usage, props, day, new IpHasher(props));
            PlacesService places =
                    new PlacesService(new SearchClient(props), limiter, day, props);

            places.suggest("palo alto", "1.1.1.1");
            places.suggest("berkeley bart", "2.2.2.2");

            assertTrue(places.suggest("san jose", "3.3.3.3").isEmpty());
            assertEquals(2, stub.requestCount(), "the third search must not call out");
            assertEquals(20, limiter.remaining(Budget.SEARCH, "3.3.3.3"),
                    "a shared limit does not cost the visitor a search");
        }
    }

    /**
     * A failed search is a missing dropdown, not a broken page: the visitor can still
     * paste coordinates.
     */
    @Test
    void anUpstreamFailureDegradesToAnEmptyList() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(503, "down")) {
            Fixture f = fixture(stub, 20, 3);

            assertTrue(f.places().suggest("palo alto", IP).isEmpty());
            assertEquals(19, f.limiter().remaining(Budget.SEARCH, IP),
                    "TomTom received it and counted it, so the visitor spent a search");
        }
    }

    /**
     * Nothing reached TomTom, so nothing is spent. Refunding only in this case, not on
     * every failure, matters here: search has no global cap, and a refund on a TomTom
     * error would let one visitor retry against a failing upstream indefinitely.
     */
    @Test
    void aSearchThatNeverConnectsCostsNoToken() {
        // Port 1 on loopback refuses connections.
        Fixture f = fixtureAt("http://127.0.0.1:1", 20, 3);

        assertTrue(f.places().suggest("palo alto", IP).isEmpty());
        assertEquals(20, f.limiter().remaining(Budget.SEARCH, IP));
    }

    @Test
    void aNullOrBlankQueryIsHandled() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, BODY)) {
            Fixture f = fixture(stub, 20, 3);

            assertTrue(f.places().suggest(null, IP).isEmpty());
            assertTrue(f.places().suggest("   ", IP).isEmpty());
            assertEquals(0, stub.requestCount());
        }
    }

    /**
     * What someone types is usually a home or work address. The service never stores
     * one, so it must not leave one in the logs either, on either failure path.
     */
    @Test
    void aFailedSearchDoesNotLogWhatWasTyped(CapturedOutput output) throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(500, "down")) {
            fixture(stub, 20, 3).places().suggest("1 Infinite Loop", IP);
        }
        fixtureAt("http://127.0.0.1:1", 20, 3).places().suggest("742 Evergreen Terrace", IP);

        assertTrue(output.getOut().contains("address search"), "the failures are logged");
        assertFalse(output.getAll().contains("Infinite Loop"));
        assertFalse(output.getAll().contains("Evergreen Terrace"));
    }
}
