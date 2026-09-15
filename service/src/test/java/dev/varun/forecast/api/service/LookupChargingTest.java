package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.varun.forecast.api.DatabaseTest;
import dev.varun.forecast.api.client.RoutingClient;
import dev.varun.forecast.api.client.StubTomTom;
import dev.varun.forecast.api.config.ForecastProperties;
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
 * What a lookup costs when a key is configured, against a local stand-in for TomTom.
 *
 * <p>The rule: a call counts once it has been sent. A call TomTom answered with an error
 * still counts, because TomTom counted it; a call that never connected does not.
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

    private LookupService lookupsAgainst(String baseUrl) {
        ForecastProperties props = TestProps.with(baseUrl, "test-key", 5, 20, 3);
        return new LookupService(corridors, samples, new RoutingClient(props), quotas,
                grids, seeded, perIp, killSwitch, props);
    }

    /** A corridor with every reduced-grid slot fresh except Friday 07:00 and 08:00. */
    private void corridorMissingTwoSlots(String origin, String dest) {
        Corridor corridor = corridors.save(new Corridor(null, origin, dest, null, false));
        for (DayOfWeek day : DayOfWeek.values()) {
            if (day.getValue() > 5) {
                continue;
            }
            for (int hour : new int[] {6, 7, 8}) {
                if (day == DayOfWeek.FRIDAY && hour > 6) {
                    continue;
                }
                samples.save(new Sample(corridor.getId(), day, hour, 1800, 40_000,
                        Instant.now().minus(1, ChronoUnit.DAYS)));
            }
        }
    }

    @Test
    void callsThatReachTomTomAreChargedEvenWhenOneIsRejected() throws Exception {
        corridorMissingTwoSlots("37.21,-122.21", "38.21,-121.21");
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
        corridorMissingTwoSlots("37.22,-122.22", "38.22,-121.22");

        // Port 1 on loopback refuses connections.
        ForecastGrid grid = lookupsAgainst("http://127.0.0.1:1")
                .lookup("37.22,-122.22", "38.22,-121.22", IP);

        assertEquals(13, grid.sampleCount());
        assertEquals(150, quotas.remaining());
        assertEquals(5, perIp.remaining(Budget.LOOKUP, IP));
    }
}
