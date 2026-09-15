package dev.varun.forecast.api.client;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A local stand-in for the TomTom API.
 *
 * <p>The clients take their base URL from configuration, so pointing them here exercises
 * the real HTTP path — URL construction, status handling, retry policy, JSON parsing —
 * without a mock and without a network. It also means no test can spend real quota.
 */
public final class StubTomTom implements AutoCloseable {

    private final HttpServer server;
    private final Deque<Response> responses = new ArrayDeque<>();
    private final List<String> requestUris = new ArrayList<>();

    public record Response(int status, String body) {}

    public StubTomTom() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            // The raw URI, so a test can assert on exactly what went over the wire.
            requestUris.add(exchange.getRequestURI().toString());
            Response next = responses.isEmpty()
                    ? new Response(200, "{\"routes\":[]}")
                    : responses.removeFirst();
            byte[] bytes = next.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(next.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
    }

    public StubTomTom enqueue(int status, String body) {
        responses.add(new Response(status, body));
        return this;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public List<String> requestUris() {
        return requestUris;
    }

    public int requestCount() {
        return requestUris.size();
    }

    public String lastUri() {
        return requestUris.get(requestUris.size() - 1);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
