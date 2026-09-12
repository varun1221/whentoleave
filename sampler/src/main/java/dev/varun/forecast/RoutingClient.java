package dev.varun.forecast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.varun.forecast.model.Route;
import dev.varun.forecast.model.RouteResult;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;

/**
 * TomTom Routing API (calculateRoute) client.
 *
 * <p>Setting {@code departAt} to a future instant makes TomTom drop live traffic and
 * price the trip off its historic speed-profile database instead. That is exactly the
 * semantic this project needs — and exactly what the README must be honest about: these
 * are historical averages reshaped into a view the vendor doesn't offer, not live
 * forecasts.
 *
 * <p>Chosen over Google Routes API because the freemium tier needs no payment method:
 * 20,000 routing requests/month against the sampler's 1,820.
 */
public final class RoutingClient {

    private static final String BASE =
            "https://api.tomtom.com/routing/1/calculateRoute/";

    /** ~2 req/sec, comfortably under the freemium QPS ceiling. */
    private static final long MIN_INTERVAL_MILLIS = 500;

    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    private long lastCallAt = 0;
    private int callsMade = 0;

    public RoutingClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("TOMTOM_API_KEY is not set");
        }
        this.apiKey = apiKey;
    }

    public int callsMade() {
        return callsMade;
    }

    /**
     * One calculateRoute call. Returns empty when TomTom finds no route between the two
     * points — a data answer, not a failure.
     *
     * @throws IOException on a non-retryable error, or after MAX_ATTEMPTS retryable ones
     */
    public Optional<RouteResult> compute(Route route, String departAtUtc)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uriFor(route, departAtUtc))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET()
                .build();

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            throttle();
            callsMade++;
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 200) {
                return parse(response.body());
            }
            if (status == 429 || status >= 500) {
                lastFailure = new IOException(
                        "HTTP " + status + " from TomTom: " + truncate(response.body()));
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(backoffMillis(attempt));
                    continue;
                }
                throw lastFailure;
            }
            // 400 is a malformed coordinate or departAt; 403 is a bad or over-quota key.
            // Neither improves on a retry, and retrying burns the monthly allowance.
            throw new IOException("HTTP " + status + " from TomTom (not retried): "
                    + truncate(response.body()));
        }
        throw lastFailure;
    }

    /**
     * calculateRoute takes its waypoints in the path as {@code lat,lon:lat,lon}, so the
     * colons and commas there must stay unescaped. Only the query values are encoded.
     */
    static URI uriFor(Route route, String departAtUtc, String apiKey) {
        String path = route.origin() + ":" + route.dest();
        String query = "key=" + encode(apiKey)
                + "&departAt=" + encode(departAtUtc)
                + "&travelMode=car"
                + "&routeType=fastest"
                + "&traffic=true";
        return URI.create(BASE + path + "/json?" + query);
    }

    private URI uriFor(Route route, String departAtUtc) {
        return uriFor(route, departAtUtc, apiKey);
    }

    static Optional<RouteResult> parseBody(String json, ObjectMapper mapper)
            throws IOException {
        JsonNode routes = mapper.readTree(json).path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            return Optional.empty();
        }
        JsonNode summary = routes.get(0).path("summary");
        if (!summary.hasNonNull("travelTimeInSeconds")) {
            return Optional.empty();
        }
        Long distance = summary.hasNonNull("lengthInMeters")
                ? summary.get("lengthInMeters").asLong()
                : null;
        return Optional.of(new RouteResult(
                summary.get("travelTimeInSeconds").asLong(), distance));
    }

    private Optional<RouteResult> parse(String json) throws IOException {
        return parseBody(json, mapper);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static long backoffMillis(int attempt) {
        return (long) (1000L * Math.pow(2, attempt - 1));
    }

    private void throttle() throws InterruptedException {
        long since = System.currentTimeMillis() - lastCallAt;
        if (since < MIN_INTERVAL_MILLIS) {
            Thread.sleep(MIN_INTERVAL_MILLIS - since);
        }
        lastCallAt = System.currentTimeMillis();
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }
}
