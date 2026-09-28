package dev.varun.forecast.api.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.api.service.PlaceSuggestion;
import dev.varun.forecast.api.service.TestProps;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SearchClientTest {

    private static final String TWO_RESULTS = """
            {"results":[
              {"position":{"lat":37.3366,"lon":-121.8806},
               "poi":{"name":"San Jose State University"},
               "address":{"freeformAddress":"211 S 9th St, San Jose, CA 95192"}},
              {"position":{"lat":37.4419,"lon":-122.1430},
               "address":{"freeformAddress":"Palo Alto, CA"}}
            ]}
            """;

    private static SearchClient clientFor(StubTomTom stub) {
        return new SearchClient(TestProps.with(stub.baseUrl(), "test-key", 5, 20, 3));
    }

    @Test
    void parsesCoordinatesAndLabels() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, TWO_RESULTS)) {
            List<PlaceSuggestion> results = clientFor(stub).suggest("san jose state");

            assertEquals(2, results.size());
            assertEquals(37.3366, results.get(0).lat());
            assertEquals(-121.8806, results.get(0).lon());
        }
    }

    /** A point of interest leads with its name; a plain address is just the address. */
    @Test
    void describesPointsOfInterestWithTheirName() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, TWO_RESULTS)) {
            List<PlaceSuggestion> results = clientFor(stub).suggest("san jose state");

            assertEquals("San Jose State University — 211 S 9th St, San Jose, CA 95192",
                    results.get(0).description());
            assertEquals("Palo Alto, CA", results.get(1).description());
        }
    }

    /** The suggestion hands back the exact string /api/lookup accepts. */
    @Test
    void exposesACoordinateTheLookupEndpointAccepts() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, TWO_RESULTS)) {
            PlaceSuggestion first = clientFor(stub).suggest("san jose state").get(0);

            assertEquals("37.3366,-121.8806", first.coord());
            assertTrue(first.coord()
                    .matches("^-?\\d{1,3}(\\.\\d+)?,\\s*-?\\d{1,3}(\\.\\d+)?$"));
        }
    }

    /**
     * The query is a path segment, where URLEncoder's "+" means a literal plus rather
     * than a space. TomTom tolerates it today, which is not a guarantee.
     */
    @Test
    void encodesSpacesForAPathSegmentNotAQueryParameter() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, TWO_RESULTS)) {
            clientFor(stub).suggest("san jose state");

            String uri = stub.lastUri();
            String path = uri.substring(0, uri.indexOf("?"));
            assertEquals("/search/2/search/san%20jose%20state.json", path);
            assertTrue(!path.contains("+"), "a path segment must not use + for a space");
        }
    }

    @Test
    void sendsTheConfiguredLimitAndCountry() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, TWO_RESULTS)) {
            clientFor(stub).suggest("palo alto");

            assertTrue(stub.lastUri().contains("limit=5"));
            assertTrue(stub.lastUri().contains("countrySet=US"));
            assertTrue(stub.lastUri().contains("lat=37.6&lon=-122.1"));
            assertTrue(stub.lastUri().contains("typeahead=true"));
            assertTrue(stub.lastUri().contains("idxSet=Geo,"), "cities must be searchable");
        }
    }

    @Test
    void skipsResultsWithNoPosition() throws Exception {
        String body = """
                {"results":[
                  {"address":{"freeformAddress":"Nowhere"}},
                  {"position":{"lat":37.0,"lon":-122.0},
                   "address":{"freeformAddress":"Somewhere"}}
                ]}
                """;
        try (StubTomTom stub = new StubTomTom().enqueue(200, body)) {
            List<PlaceSuggestion> results = clientFor(stub).suggest("anything");

            assertEquals(1, results.size());
            assertEquals("Somewhere", results.get(0).description());
        }
    }

    @Test
    void noResultsIsAnEmptyListNotAnError() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(200, "{\"results\":[]}")) {
            assertTrue(clientFor(stub).suggest("zzzzzz").isEmpty());
        }
    }

    /** One attempt only: this sits behind a keystroke, so it must fail fast. */
    @Test
    void doesNotRetry() throws Exception {
        try (StubTomTom stub = new StubTomTom().enqueue(503, "down")) {
            assertThrows(IOException.class, () -> clientFor(stub).suggest("palo alto"));
            assertEquals(1, stub.requestCount());
        }
    }

    @Test
    void refusesToCallWithoutAnApiKey() {
        SearchClient client =
                new SearchClient(TestProps.with("http://127.0.0.1:1", "", 5, 20, 3));

        IOException thrown =
                assertThrows(IOException.class, () -> client.suggest("palo alto"));
        assertTrue(thrown.getMessage().contains("TOMTOM_API_KEY"));
    }
}
