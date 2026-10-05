package dev.varun.forecast.api.client;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A local stand-in for the TomTom API.
 *
 * <p>The clients take their base URL from configuration, so pointing them here exercises
 * the real HTTP path — URL construction, status handling, retry policy, JSON parsing —
 * without a mock and without a network. It also means no test can spend real quota.
 */
public final class StubTomTom implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    private final Deque<Response> responses = new ArrayDeque<>();
    private final List<String> requestUris = new ArrayList<>();
    private final List<Long> arrivalNanos = new ArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    public record Response(int status, String body) {}

    /** Answers one request at a time, immediately. */
    public StubTomTom() throws IOException {
        this(Duration.ZERO);
    }

    /**
     * Answers each request after {@code latency}, several at once, so a test can see
     * whether a caller overlaps its requests and how closely it spaces them.
     */
    public StubTomTom(Duration latency) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = latency.isZero() ? null : Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            Response next;
            synchronized (this) {
                // The raw URI, so a test can assert on exactly what went over the wire.
                requestUris.add(exchange.getRequestURI().toString());
                arrivalNanos.add(System.nanoTime());
                next = responses.isEmpty()
                        ? new Response(200, "{\"routes\":[]}")
                        : responses.removeFirst();
            }
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            try {
                Thread.sleep(latency.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            byte[] bytes = next.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(next.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    public synchronized StubTomTom enqueue(int status, String body) {
        responses.add(new Response(status, body));
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public synchronized List<String> requestUris() {
        return List.copyOf(requestUris);
    }

    public synchronized int requestCount() {
        return requestUris.size();
    }

    public synchronized String lastUri() {
        return requestUris.get(requestUris.size() - 1);
    }

    /** The most requests that were ever being answered at the same moment. */
    public int maxInFlight() {
        return maxInFlight.get();
    }

    /** The shortest gap between two consecutive requests arriving. */
    public synchronized Duration shortestGap() {
        long shortest = Long.MAX_VALUE;
        for (int i = 1; i < arrivalNanos.size(); i++) {
            shortest = Math.min(shortest, arrivalNanos.get(i) - arrivalNanos.get(i - 1));
        }
        return Duration.ofNanos(shortest);
    }

    @Override
    public void close() {
        server.stop(0);
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
