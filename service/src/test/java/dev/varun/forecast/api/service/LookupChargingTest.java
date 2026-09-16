package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.api.DatabaseTest;
import dev.varun.forecast.api.client.CallBudget;
import dev.varun.forecast.api.client.RoutingClient;
import dev.varun.forecast.api.client.StubTomTom;
import dev.varun.forecast.api.config.ForecastProperties;
import dev.varun.forecast.api.domain.Corridor;
import dev.varun.forecast.api.domain.Sample;
import dev.varun.forecast.api.repo.CorridorRepository;
import dev.varun.forecast.api.repo.SampleRepository;
import dev.varun.forecast.api.service.DailyIpLimiter.Budget;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * What a lookup costs when a key is configured, against a local stand-in for TomTom.
 *
 * <p>The rule: a call counts once it has been sent. A call TomTom answered with an error
 * still counts, because TomTom counted it; a call that never connected does not. A retry
 * is a call like any other.
 *
 * <p>Built by hand around the context's real quota, limiter and repositories, so only
 * the routing client points somewhere other than the test configuration.
 */
class LookupChargingTest extends DatabaseTest {

    private static final String ROUTE = """
            {"routes":[{"summary":{"travelTimeInSeconds":1800,"lengthInMeters":40000}}]}
            """;
    private static final String IP = "10.1.0.1";

    @Autowired private CorridorRepository corridors;
    @Autowired private SampleRepository samples;
    @Autowired private QuotaService quotas;
    @Autowired private GridBuilder grids;
    @Autowired private ForecastService seeded;
    @Autowired private DailyIpLimiter perIp;
    @Autowired private KillSwitch killSwitch;
    @Autowired private QuotaDay day;

    private LookupService lookupsAgainst(String baseUrl) {
        ForecastProperties props = TestProps.with(baseUrl, "test-key", 5, 20, 3);
        return lookupsUsing(props, new RoutingClient(props));
    }

    private LookupService lookupsUsing(ForecastProperties props, RoutingClient routing) {
        return new LookupService(corridors, samples, routing, quotas, grids, seeded,
                perIp, killSwitch, day, props, Clock.systemUTC());
    }

    /**
     * A lookup whose routing client does whatever the test says, for the failures HTTP
     * cannot produce on demand — an interrupt mid-request, or the unexpected
     * {@code RuntimeException} that a failing {@code samples.save} would throw.
     */
    private LookupService lookupsWhereEachCall(Call call) {
        ForecastProperties props =
                TestProps.with("http://127.0.0.1:1", "test-key", 5, 20, 3);
        return lookupsUsing(props, new RoutingClient(props) {
            @Override
            public Optional<RouteResult> compute(String origin, String dest,
                    ZonedDateTime departAt, CallBudget budget)
                    throws InterruptedException {
                return call.of(budget);
            }
        });
    }

    @FunctionalInterface
    private interface Call {
        Optional<RoutingClient.RouteResult> of(CallBudget budget)
                throws InterruptedException;
    }

    /** A corridor whose reduced grid is fresh except for its last {@code gaps} slots. */
    private void corridorMissing(String origin, String dest, int gaps) {
        Corridor corridor = corridors.save(new Corridor(null, origin, dest, null, false));
        List<Slot> slots = new ArrayList<>();
        for (DayOfWeek dow : DayOfWeek.values()) {
            if (dow.getValue() > 5) {
                continue;
            }
            for (int hour : new int[] {6, 7, 8}) {
                slots.add(new Slot(dow, hour));
            }
        }
        for (Slot slot : slots.subList(0, slots.size() - gaps)) {
            samples.save(new Sample(corridor.getId(), slot.day(), slot.hour(), 1800,
                    40_000, Instant.now().minus(1, ChronoUnit.DAYS)));
        }
    }

    @Test
    void callsThatReachTomTomAreChargedEvenWhenOneIsRejected() throws Exception {
        corridorMissing("37.21,-122.21", "38.21,-121.21", 2);
        try (StubTomTom stub = new StubTomTom().enqueue(200, ROUTE).enqueue(400, "bad")) {

            ForecastGrid grid = lookupsAgainst(stub.baseUrl())
                    .lookup("37.21,-122.21", "38.21,-121.21", IP);

            assertEquals(2, stub.requestCount());
            assertEquals(14, grid.sampleCount(), "13 cached plus the one that succeeded");
            assertEquals(148, quotas.remaining(), "both calls were sent, so both count");
            assertEquals(4, perIp.remaining(Budget.LOOKUP, IP), "one lookup spent");
        }
    }

    /** TomTom unreachable: nothing left the process, so nothing is charged. */
    @Test
    void callsThatNeverConnectAreNotCharged() {
        corridorMissing("37.22,-122.22", "38.22,-121.22", 2);

        // Port 1 on loopback refuses connections.
        ForecastGrid grid = lookupsAgainst("http://127.0.0.1:1")
                .lookup("37.22,-122.22", "38.22,-121.22", IP);

        assertEquals(13, grid.sampleCount());
        assertEquals(150, quotas.remaining());
        assertEquals(5, perIp.remaining(Budget.LOOKUP, IP));
    }

    /**
     * §7: "this application-level counter <em>is</em> the hard stop". A retry is a second
     * real request, so charging one call for a slot that took two would let a bad day at
     * TomTom run to three times the ceiling.
     */
    @Test
    void aRetriedSlotIsChargedForEveryRequestItSent() throws Exception {
        corridorMissing("37.23,-122.23", "38.23,-121.23", 1);
        try (StubTomTom stub = new StubTomTom().enqueue(503, "down").enqueue(200, ROUTE)) {

            ForecastGrid grid = lookupsAgainst(stub.baseUrl())
                    .lookup("37.23,-122.23", "38.23,-121.23", IP);

            assertEquals(2, stub.requestCount(), "the 503 was retried");
            assertEquals(15, grid.sampleCount(), "14 cached plus the retried slot");
            assertEquals(148, quotas.remaining(), "the retry counts too");
        }
    }

    /** And when the ceiling cannot cover the retry, the slot fails instead of it. */
    @Test
    void aRetryIsNotSentWhenTheCeilingIsReached() throws Exception {
        corridorMissing("37.24,-122.24", "38.24,-121.24", 1);
        quotas.reserve(149);
        try (StubTomTom stub = new StubTomTom().enqueue(503, "down").enqueue(200, ROUTE)) {

            ForecastGrid grid = lookupsAgainst(stub.baseUrl())
                    .lookup("37.24,-122.24", "38.24,-121.24", IP);

            assertEquals(1, stub.requestCount(), "the last call went on the first try");
            assertEquals(14, grid.sampleCount(), "so the slot stays empty");
            assertEquals(0, quotas.remaining(), "and the ceiling held");
        }
    }

    /**
     * An interrupt arrives between sending a request and reading its answer. TomTom may
     * well have received it, so it is charged: guessing the other way is how a shutdown
     * during a fill turns into free calls.
     */
    @Test
    void anInterruptedSlotCountsAsSent() {
        corridorMissing("37.25,-122.25", "38.25,-121.25", 3);

        ForecastGrid grid = lookupsWhereEachCall(budget -> {
            budget.tryAcquire();
            throw new InterruptedException("shutting down");
        }).lookup("37.25,-122.25", "38.25,-121.25", IP);

        assertTrue(Thread.interrupted(), "the interrupt is passed on, not swallowed");
        assertEquals(12, grid.sampleCount(), "the fill stopped at the interrupt");
        assertEquals(149, quotas.remaining(), "the interrupted call may have been sent");
        assertEquals(4, perIp.remaining(Budget.LOOKUP, IP));
    }

    /**
     * §9.4 on the path nobody plans for. Without the release, every unexpected failure
     * would strand its unsent reservations and walk the ceiling down until the service
     * stopped calling out at all.
     */
    @Test
    void anUnexpectedFailureStillReleasesTheCallsItNeverSent() {
        corridorMissing("37.26,-122.26", "38.26,-121.26", 3);
        int[] calls = {0};

        assertThrows(IllegalStateException.class, () -> lookupsWhereEachCall(budget -> {
            if (calls[0]++ > 0) {
                throw new IllegalStateException("could not save the sample");
            }
            budget.tryAcquire();
            return Optional.of(new RoutingClient.RouteResult(1800, 40_000));
        }).lookup("37.26,-122.26", "38.26,-121.26", IP));

        assertEquals(149, quotas.remaining(), "the two unsent calls went back");
        assertEquals(4, perIp.remaining(Budget.LOOKUP, IP), "one call did reach TomTom");
    }

    /** The same failure before anything went out also gives the visitor their lookup back. */
    @Test
    void anUnexpectedFailureBeforeAnyCallRefundsTheVisitor() {
        corridorMissing("37.27,-122.27", "38.27,-121.27", 3);

        assertThrows(IllegalStateException.class, () -> lookupsWhereEachCall(budget -> {
            throw new IllegalStateException("could not save the sample");
        }).lookup("37.27,-122.27", "38.27,-121.27", IP));

        assertEquals(150, quotas.remaining());
        assertEquals(5, perIp.remaining(Budget.LOOKUP, IP));
    }
}
