package dev.varun.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.varun.forecast.model.Route;
import dev.varun.forecast.model.RouteResult;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RoutingClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Route ROUTE =
            new Route("sjsu-sf", "SJSU → SF", "37.3352,-121.8811", "37.7946,-122.3999");

    @Test
    void rejectsAnEmptyApiKeyRatherThanCallingWithIt() {
        assertThrows(IllegalArgumentException.class, () -> new RoutingClient(""));
        assertThrows(IllegalArgumentException.class, () -> new RoutingClient(null));
    }

    /** The waypoints are path segments; escaping the colon would 404 the request. */
    @Test
    void waypointsStayUnescapedInThePath() {
        URI uri = RoutingClient.uriFor(ROUTE, "2026-09-02T15:00:00Z", "abc123");
        assertTrue(uri.toString().contains(
                        "/calculateRoute/37.3352,-121.8811:37.7946,-122.3999/json"),
                uri.toString());
    }

    /** departAt is a query value, so its colons must be escaped. */
    @Test
    void departAtIsUrlEncodedInTheQuery() {
        URI uri = RoutingClient.uriFor(ROUTE, "2026-09-02T15:00:00Z", "abc123");
        assertTrue(uri.toString().contains("departAt=2026-09-02T15%3A00%3A00Z"),
                uri.toString());
    }

    @Test
    void requestsTrafficAwareFastestCarRouting() {
        String uri = RoutingClient.uriFor(ROUTE, "2026-09-02T15:00:00Z", "abc").toString();
        assertTrue(uri.contains("travelMode=car"), uri);
        assertTrue(uri.contains("routeType=fastest"), uri);
        assertTrue(uri.contains("traffic=true"), uri);
    }

    @Test
    void parsesTravelTimeAndDistanceFromTheSummary() throws IOException {
        String body = """
                {"routes":[{"summary":{
                  "lengthInMeters":78234,
                  "travelTimeInSeconds":3120,
                  "trafficDelayInSeconds":420}}]}
                """;
        RouteResult result = RoutingClient.parseBody(body, MAPPER).orElseThrow();
        assertEquals(3120L, result.durationSeconds());
        assertEquals(78234L, result.distanceMeters());
    }

    @Test
    void noRouteFoundIsEmptyNotACrash() throws IOException {
        assertEquals(Optional.empty(),
                RoutingClient.parseBody("{\"routes\":[]}", MAPPER));
        assertEquals(Optional.empty(), RoutingClient.parseBody("{}", MAPPER));
    }

    @Test
    void aSummaryWithoutTravelTimeIsTreatedAsNoRoute() throws IOException {
        String body = "{\"routes\":[{\"summary\":{\"lengthInMeters\":100}}]}";
        assertEquals(Optional.empty(), RoutingClient.parseBody(body, MAPPER));
    }

    @Test
    void missingDistanceIsNullNotZero() throws IOException {
        String body = "{\"routes\":[{\"summary\":{\"travelTimeInSeconds\":3120}}]}";
        assertNull(RoutingClient.parseBody(body, MAPPER).orElseThrow().distanceMeters());
    }
}
