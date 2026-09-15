package dev.varun.forecast.api.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.varun.forecast.api.config.ForecastProperties;
import dev.varun.forecast.api.service.PlaceSuggestion;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * TomTom Fuzzy Search, server side.
 *
 * <p>This exists so the API key never reaches a browser. A frontend calling TomTom
 * directly would have to ship the key to every visitor, and a key in a static bundle is
 * a key anyone can spend.
 */
@Component
public class SearchClient {

    private final ForecastProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    public SearchClient(ForecastProperties props) {
        this.props = props;
    }

    /**
     * Suggestions for a partial address.
     *
     * <p>One attempt, no retries: this sits behind a keystroke. A visitor who gets no
     * suggestions types one more character and asks again, which is a better outcome
     * than a request that retries for three seconds against a stale query.
     */
    public List<PlaceSuggestion> suggest(String query) throws IOException,
            InterruptedException {
        if (!props.tomtom().configured()) {
            throw new CallNotSentException("TOMTOM_API_KEY is not configured");
        }
        // The query sits in the path for this endpoint, so it must be encoded — unlike
        // the routing waypoints, where encoding the separator breaks the request.
        String url = props.tomtom().baseUrl()
                + "/search/2/search/" + pathSegment(query) + ".json"
                + "?key=" + enc(props.tomtom().apiKey())
                + "&limit=" + props.search().resultLimit()
                + "&countrySet=" + enc(props.search().countrySet())
                + "&typeahead=true&idxSet=PAD,Str,POI";

        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw new CallNotSentException("could not connect to TomTom", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("fuzzy search returned HTTP " + response.statusCode());
        }
        return parse(response.body());
    }

    private List<PlaceSuggestion> parse(String body) throws IOException {
        JsonNode results = mapper.readTree(body).path("results");
        List<PlaceSuggestion> out = new ArrayList<>();
        if (!results.isArray()) {
            return out;
        }
        for (JsonNode result : results) {
            JsonNode position = result.path("position");
            JsonNode lat = position.path("lat");
            JsonNode lon = position.path("lon");
            if (lat.isMissingNode() || lon.isMissingNode()) {
                continue;
            }
            out.add(new PlaceSuggestion(lat.asDouble(), lon.asDouble(), describe(result)));
        }
        return out;
    }

    /** A point of interest leads with its name; a plain address is just the address. */
    private static String describe(JsonNode result) {
        String address = result.path("address").path("freeformAddress").asText("");
        String poi = result.path("poi").path("name").asText("");
        if (!poi.isBlank() && !address.isBlank()) {
            return poi + " — " + address;
        }
        return poi.isBlank() ? address : poi;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * The query is a path segment, not a query parameter, and the two encode spaces
     * differently: {@code URLEncoder} emits "+", which means a literal plus sign in a
     * path. TomTom happens to tolerate it today, which is exactly the kind of thing that
     * stops being true without warning.
     */
    private static String pathSegment(String value) {
        return enc(value).replace("+", "%20");
    }
}
