package dev.varun.forecast.api.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.varun.forecast.api.config.ForecastProperties;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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

    /**
     * 4 req/s, under TomTom's default 5 QPS for Routing. Per instance: with Cloud Run's
     * two, simultaneous cold lookups on both can briefly exceed it, and the 429s that
     * follow are retried like any other.
     */
    private static final long MIN_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    private static final int MAX_ATTEMPTS = 3;

    private final ForecastProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** When the next request may go out, shared by every thread calling this client. */
    private long nextSendAt = System.nanoTime();

    public RoutingClient(ForecastProperties props) {
        this.props = props;
    }

    /**
     * One route for one future departure time. Safe to call from several threads at
     * once: requests are spaced across all of them, not per caller.
     *
     * <p>Every attempt is charged to {@code budget} before it goes out, retries
     * included: §9.4 counts what TomTom receives, not what the caller set out to do.
     * When the budget cannot cover an attempt it is not sent, and this route fails
     * instead — §7 makes the ceiling the hard stop.
     *
     * @return empty when TomTom finds no route, which is a normal answer rather than an
     *     error
     * @throws CallNotSentException when nothing reached TomTom, so the call cost nothing
     * @throws IOException on a non-retryable failure, or after exhausting retries
     */
    public Optional<RouteResult> compute(String origin, String dest,
            ZonedDateTime departAt, CallBudget budget)
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
            // Waited for before the call is claimed: a fill queues all its calls here at
            // once, so an interrupt mid-fill finds most of them still waiting, and none
            // of those has gone out.
            throttle();
            if (!budget.tryAcquire()) {
                if (attempt == 1) {
                    throw new CallNotSentException("no calls left in today's budget");
                }
                throw new IOException("calculateRoute had no budget to retry after "
                        + (attempt - 1) + " attempts");
            }
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
                    budget.refund();
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

    /**
     * Books the next free send time and waits for it. The booking is under the lock and
     * the wait is not, so callers queue in booking order without holding each other up.
     */
    private void throttle() throws InterruptedException {
        long wait;
        synchronized (this) {
            long now = System.nanoTime();
            long sendAt = Math.max(now, nextSendAt);
            nextSendAt = sendAt + MIN_INTERVAL_NANOS;
            wait = sendAt - now;
        }
        TimeUnit.NANOSECONDS.sleep(wait);
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
