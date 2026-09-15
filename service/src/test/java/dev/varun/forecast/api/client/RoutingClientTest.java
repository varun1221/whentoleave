package dev.varun.forecast.api.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.api.service.TestProps;
import java.io.IOException;
import java.time.ZonedDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoutingClientTest {

    private static final String ORIGIN = "37.3352,-121.8811";
    private static final String DEST = "37.7946,-122.3999";
    private static final ZonedDateTime DEPART_AT =
            ZonedDateTime.parse("2026-09-16T08:00:00-07:00[America/Los_Angeles]");

    private static final String OK_BODY = """
            {"routes":[{"summary":{
              "lengthInMeters":78234,
              "travelTimeInSeconds":3120,
              "trafficDelayInSeconds":420}}]}
            """;

    private static RoutingClient clientFor(StubTomTom stub) {
        return new RoutingClient(TestProps.with(stub.baseUrl(), "test-key", 5, 20, 3));
    }

    /**
     * The colon between waypoints is a path separator. Encoding it 404s the request, so
     * this is the assertion that stops a well-meaning "fix" from breaking every call.
     */
    @Test
    void waypointsStayUnescapedInThePath() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, OK_BODY)) {
            clientFor(stub).compute(ORIGIN, DEST, DEPART_AT);

            String uri = stub.lastUri();
            // Only the path is asserted on: the query legitimately contains encoded
            // colons, from departAt.
            String path = uri.substring(0, uri.indexOf("?"));
            assertEquals("/routing/1/calculateRoute/" + ORIGIN + ":" + DEST + "/json",
                    path);
            assertFalse(path.contains("%3A"), "the waypoint colon must not be encoded");
            assertFalse(path.contains("%2C"), "the coordinate comma must not be encoded");
        }
    }

    @Test
    void departAtIsUrlEncodedInTheQueryAsUtc() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, OK_BODY)) {
            clientFor(stub).compute(ORIGIN, DEST, DEPART_AT);

            // 08:00 Pacific in September is 15:00 UTC.
            assertTrue(stub.lastUri().contains("departAt=2026-09-16T15%3A00%3A00Z"),
                    "departAt must be encoded UTC, got: " + stub.lastUri());
        }
    }

    @Test
    void sendsTheRoutingParametersTheProjectDependsOn() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, OK_BODY)) {
            clientFor(stub).compute(ORIGIN, DEST, DEPART_AT);

            String uri = stub.lastUri();
            assertTrue(uri.contains("travelMode=car"));
            assertTrue(uri.contains("routeType=fastest"));
            assertTrue(uri.contains("traffic=true"));
            assertTrue(uri.contains("key=test-key"));
        }
    }

    @Test
    void readsDurationAndDistanceFromTheSummary() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, OK_BODY)) {
            Optional<RoutingClient.RouteResult> result =
                    clientFor(stub).compute(ORIGIN, DEST, DEPART_AT);

            assertTrue(result.isPresent());
            assertEquals(3120L, result.get().durationSeconds());
            assertEquals(78234, result.get().distanceMeters());
        }
    }

    /** No route is a normal answer, not a failure. */
    @Test
    void anEmptyRoutesArrayIsEmptyNotAnError() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, "{\"routes\":[]}")) {
            assertTrue(clientFor(stub).compute(ORIGIN, DEST, DEPART_AT).isEmpty());
        }
    }

    @Test
    void aSummaryWithoutTravelTimeIsEmptyRatherThanACrash() throws Exception {
        String body = "{\"routes\":[{\"summary\":{\"lengthInMeters\":78234}}]}";
        try (StubTomTom stub = new StubTomTom().enqueue(200, body)) {
            assertTrue(clientFor(stub).compute(ORIGIN, DEST, DEPART_AT).isEmpty());
        }
    }

    @Test
    void missingDistanceIsNullNotZero() throws Exception {
        String body = "{\"routes\":[{\"summary\":{\"travelTimeInSeconds\":3120}}]}";
        try (StubTomTom stub = new StubTomTom().enqueue(200, body)) {
            Optional<RoutingClient.RouteResult> result =
                    clientFor(stub).compute(ORIGIN, DEST, DEPART_AT);

            assertTrue(result.isPresent());
            assertNull(result.get().distanceMeters());
        }
    }

    @Test
    void retriesA429AndSucceeds() throws Exception {
        try (StubTomTom stub = new StubTomTom()
                .enqueue(429, "{\"error\":\"rate limited\"}")
                .enqueue(200, OK_BODY)) {
            Optional<RoutingClient.RouteResult> result =
                    clientFor(stub).compute(ORIGIN, DEST, DEPART_AT);

            assertTrue(result.isPresent());
            assertEquals(2, stub.requestCount());
        }
    }

    @Test
    void retriesA500AndSucceeds() throws Exception {
        try (StubTomTom stub = new StubTomTom()
                .enqueue(503, "upstream down")
                .enqueue(200, OK_BODY)) {
            assertTrue(clientFor(stub).compute(ORIGIN, DEST, DEPART_AT).isPresent());
            assertEquals(2, stub.requestCount());
        }
    }

    /**
     * A 400 is a malformed coordinate and a 403 is a bad key. Neither can succeed on a
     * retry, and retrying burns the monthly allowance against a request that cannot work.
     */
    @Test
    void doesNotRetryA400() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(400, "bad request")) {
            assertThrows(IOException.class,
                    () -> clientFor(stub).compute(ORIGIN, DEST, DEPART_AT));
            assertEquals(1, stub.requestCount(), "a 400 must not be retried");
        }
    }

    @Test
    void doesNotRetryA403() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(403, "forbidden")) {
            assertThrows(IOException.class,
                    () -> clientFor(stub).compute(ORIGIN, DEST, DEPART_AT));
            assertEquals(1, stub.requestCount(), "a 403 must not be retried");
        }
    }

    @Test
    void givesUpAfterThreeAttempts() throws Exception {
        try (StubTomTom stub = new StubTomTom()
                .enqueue(500, "x").enqueue(500, "x").enqueue(500, "x")) {
            assertThrows(IOException.class,
                    () -> clientFor(stub).compute(ORIGIN, DEST, DEPART_AT));
            assertEquals(3, stub.requestCount());
        }
    }

    @Test
    void refusesToCallWithoutAnApiKey() {
        RoutingClient client =
                new RoutingClient(TestProps.with("http://127.0.0.1:1", "", 5, 20, 3));

        IOException thrown = assertThrows(IOException.class,
                () -> client.compute(ORIGIN, DEST, DEPART_AT));
        assertTrue(thrown.getMessage().contains("TOMTOM_API_KEY"));
    }
}
