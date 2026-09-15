package dev.varun.forecast.api.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.varun.forecast.api.config.ForecastProperties;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * TomTom calculateRoute, service side.
 *
 * <p>Deliberately not shared with the Phase 1 sampler's client. They look similar but
 * answer to different pressures: the sampler runs unattended against a known route list
 * and may take four minutes, while this one sits on a request path and must fail fast.
 * If the duplication grows past this, the spec's optional {@code shared/} module is the
 * place for it.
 */
@Component
public class RoutingClient {

    private static final Logger log = LoggerFactory.getLogger(RoutingClient.class);

    /** ~2 req/s, comfortably inside the freemium rate limit. */
    private static final long MIN_INTERVAL_MILLIS = 500;
    private static final int MAX_ATTEMPTS = 3;

    private final ForecastProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private long lastCallAt;

    public RoutingClient(ForecastProperties props) {
        this.props = props;
    }

    /**
     * One route for one future departure time.
     *
     * @return empty when TomTom finds no route, which is a normal answer rather than an
     *     error
     * @throws CallNotSentException when nothing reached TomTom, so the call cost nothing
     * @throws IOException on a non-retryable failure, or after exhausting retries
     */
    public Optional<RouteResult> compute(String origin, String dest, ZonedDateTime departAt)
            throws IOException, InterruptedException {
        if (!props.tomtom().configured()) {
            throw new CallNotSentException("TOMTOM_API_KEY is not configured");
        }
        // The colon between waypoints is a path separator. URL-encoding it 404s the
        // request, so only the query values get encoded.
        String departAtUtc = DateTimeFormatter.ISO_INSTANT
                .format(departAt.toInstant().truncatedTo(ChronoUnit.SECONDS));
        String url = props.tomtom().baseUrl()
                + "/routing/1/calculateRoute/" + origin + ":" + dest + "/json"
                + "?key=" + enc(props.tomtom().apiKey())
                + "&departAt=" + enc(departAtUtc)
                + "&travelMode=car&routeType=fastest&traffic=true";

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            throttle();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (ConnectException | HttpConnectTimeoutException e) {
                // Only the first attempt: after that an earlier attempt was received.
                if (attempt == 1) {
                    throw new CallNotSentException("could not connect to TomTom", e);
                }
                throw e;
            }
            int status = response.statusCode();

            if (status == 200) {
                return parse(response.body());
            }
            // Retry only what can succeed on a retry. A 400 is a malformed coordinate
            // and a 403 is a bad key; retrying either just burns the monthly allowance.
            if ((status == 429 || status >= 500) && attempt < MAX_ATTEMPTS) {
                Thread.sleep(backoffMillis(attempt));
                continue;
            }
            throw new IOException("calculateRoute returned HTTP " + status);
        }
        throw new IOException("calculateRoute exhausted " + MAX_ATTEMPTS + " attempts");
    }

    private Optional<RouteResult> parse(String body) throws IOException {
        JsonNode routes = mapper.readTree(body).path("routes");
        if (!routes.isArray() || routes.isEmpty()) {
            return Optional.empty();
        }
        JsonNode summary = routes.get(0).path("summary");
        JsonNode travelTime = summary.path("travelTimeInSeconds");
        if (travelTime.isMissingNode() || travelTime.isNull()) {
            log.warn("summary had no travelTimeInSeconds");
            return Optional.empty();
        }
        JsonNode length = summary.path("lengthInMeters");
        return Optional.of(new RouteResult(
                travelTime.asLong(),
                length.isMissingNode() || length.isNull() ? null : length.asInt()));
    }

    private void throttle() throws InterruptedException {
        long since = System.currentTimeMillis() - lastCallAt;
        if (since < MIN_INTERVAL_MILLIS) {
            Thread.sleep(MIN_INTERVAL_MILLIS - since);
        }
        lastCallAt = System.currentTimeMillis();
    }

    private static long backoffMillis(int attempt) {
        return 500L * (1L << attempt);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Travel time and distance for one departure. */
    public record RouteResult(long durationSeconds, Integer distanceMeters) {}
}
