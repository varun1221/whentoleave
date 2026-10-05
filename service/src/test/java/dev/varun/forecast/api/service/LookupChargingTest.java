package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        return lookupsUsing(corridors, props, routing);
    }

    private LookupService lookupsUsing(CorridorRepository corridors,
            ForecastProperties props, RoutingClient routing) {
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

    /**
     * A cold corridor is a call per slot. Waiting for each answer before sending the
     * next doubled the time a cold lookup took, so they go out together, each still
     * spaced to the rate TomTom allows. The latency is longer than that spacing, so a fill that waited for
     * each answer before sending the next could never have two in flight.
     */
    @Test
    void aColdLookupOverlapsItsCallsWithoutExceedingTheRate() throws Exception {
        try (StubTomTom stub = new StubTomTom(Duration.ofMillis(600))) {
            for (int i = 0; i < 15; i++) {
                stub.enqueue(200, ROUTE);
            }

            ForecastGrid grid = lookupsAgainst(stub.baseUrl())
                    .lookup("37.32,-122.32", "38.32,-121.32", IP);

            assertEquals(15, grid.sampleCount());
            assertEquals(135, quotas.remaining());
            assertTrue(stub.maxInFlight() > 1,
                    "calls overlapped: max in flight " + stub.maxInFlight());
            // Below the 250 ms spacing only by scheduler jitter: a sender that wakes late
            // narrows the gap to the next one, which woke on time.
            assertTrue(stub.shortestGap().toMillis() >= 150,
                    "calls stayed paced: shortest gap " + stub.shortestGap());
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

    @Test
    void anUnknownPairThatReachesTomTomRegistersACorridor() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, ROUTE)) {

            ForecastGrid grid = lookupsAgainst(stub.baseUrl())
                    .lookup("37.28,-122.28", "38.28,-121.28", IP);

            assertEquals(1, grid.sampleCount(), "the one answered slot");
            assertEquals(1, corridors.count());
            Corridor created = corridors.findAll().get(0);
            assertNull(created.getSlug(), "a user corridor has no slug");
            assertFalse(created.isSeeded());
        }
    }

    /** The unreachable case, which is when a kept row would cost nothing to repeat. */
    @Test
    void anUnknownPairThatNeverConnectsLeavesNoCorridor() {
        lookupsAgainst("http://127.0.0.1:1").lookup("37.29,-122.29", "38.29,-121.29", IP);

        assertEquals(0, corridors.count());
        assertEquals(150, quotas.remaining());
        assertEquals(5, perIp.remaining(Budget.LOOKUP, IP));
    }

    /** An unexpected failure before any call must not strand a new row either. */
    @Test
    void anUnexpectedFailureBeforeAnyCallLeavesNoCorridor() {
        assertThrows(IllegalStateException.class, () -> lookupsWhereEachCall(budget -> {
            throw new IllegalStateException("could not save the sample");
        }).lookup("37.31,-122.31", "38.31,-121.31", IP));

        assertEquals(0, corridors.count());
    }

    /**
     * The race on a new pair: another request registers it between this one's lookup
     * and its own registration. This one files its samples under that row rather than
     * failing on the unique constraint.
     */
    @Test
    void aPairRegisteredConcurrentlyIsShared() throws Exception {
        String origin = "37.302,-122.302";
        String dest = "38.302,-121.302";
        Corridor theirs = corridors.save(new Corridor(null, origin, dest, null, false));
        try (StubTomTom stub = new StubTomTom().enqueue(200, ROUTE)) {
            ForecastProperties props = TestProps.with(stub.baseUrl(), "test-key", 5, 20, 3);

            ForecastGrid grid = lookupsUsing(corridorsFirstMissing(), props,
                    new RoutingClient(props)).lookup(origin, dest, IP);

            assertEquals(1, grid.sampleCount());
            assertEquals(1, corridors.count(), "no second row");
            assertEquals(1, samples.findByCorridorId(theirs.getId()).size());
        }
    }

    /** The real repository, except the first lookup by coordinates finds nothing. */
    private CorridorRepository corridorsFirstMissing() {
        AtomicBoolean hidden = new AtomicBoolean();
        return (CorridorRepository) Proxy.newProxyInstance(
                CorridorRepository.class.getClassLoader(),
                new Class<?>[] {CorridorRepository.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("findByOriginCoordAndDestCoord")
                            && hidden.compareAndSet(false, true)) {
                        return Optional.empty();
                    }
                    try {
                        return method.invoke(corridors, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
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
     * The lookup is interrupted while its calls are between sending a request and
     * reading the answer. TomTom may well have received them, so they are charged:
     * guessing the other way is how a shutdown during a fill turns into free calls.
     */
    @Test
    void anInterruptedFillCountsItsCallsAsSent() throws Exception {
        corridorMissing("37.25,-122.25", "38.25,-121.25", 3);
        CountDownLatch allSent = new CountDownLatch(3);
        LookupService lookups = lookupsWhereEachCall(budget -> {
            budget.tryAcquire();
            allSent.countDown();
            Thread.sleep(60_000);
            return Optional.of(new RoutingClient.RouteResult(1800, 40_000));
        });
        AtomicReference<ForecastGrid> grid = new AtomicReference<>();
        AtomicBoolean passedOn = new AtomicBoolean();

        Thread request = new Thread(() -> {
            grid.set(lookups.lookup("37.25,-122.25", "38.25,-121.25", IP));
            passedOn.set(Thread.currentThread().isInterrupted());
        });
        request.start();
        allSent.await();
        request.interrupt();
        request.join(10_000);

        assertFalse(request.isAlive(), "the fill stopped at the interrupt");
        assertTrue(passedOn.get(), "the interrupt is passed on, not swallowed");
        assertEquals(12, grid.get().sampleCount(), "no answer was read");
        assertEquals(147, quotas.remaining(), "the interrupted calls may have been sent");
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
        AtomicInteger calls = new AtomicInteger();

        assertThrows(IllegalStateException.class, () -> lookupsWhereEachCall(budget -> {
            if (calls.getAndIncrement() > 0) {
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
