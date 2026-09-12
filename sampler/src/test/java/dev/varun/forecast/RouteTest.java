package dev.varun.forecast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.varun.forecast.model.Route;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteTest {

    @Test
    void acceptsCoordinatesPastedWithASpace() {
        Route route = new Route("x", "X", "37.3352, -121.8811", "37.7946, -122.3999");
        assertTrue(route.isConfigured());
        assertEquals("37.3352,-121.8811", route.origin());
    }

    @Test
    void rejectsAnythingThatIsNotACoordinatePair() {
        assertFalse(new Route("x", "X", "REPLACE_ME", "37.7946,-122.3999").isConfigured());
        assertFalse(new Route("x", "X", "", "37.7946,-122.3999").isConfigured());
        assertFalse(new Route("x", "X", null, "37.7946,-122.3999").isConfigured());
        assertFalse(new Route("x", "X", "ChIJ_abc", "37.7946,-122.3999").isConfigured());
    }

    /** The shipped config must be runnable as committed, not a template. */
    @Test
    void everyRouteInTheShippedConfigIsUsable() throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<Route> routes = mapper.readValue(
                Files.readString(Path.of("../config/routes.json")),
                mapper.getTypeFactory().constructCollectionType(List.class, Route.class));

        assertEquals(5, routes.size());
        for (Route route : routes) {
            assertTrue(route.isConfigured(), route.id() + " has unusable coordinates");
        }
        assertEquals(5, routes.stream().map(Route::id).distinct().count(),
                "route ids are used as filenames and must be unique");
    }
}
