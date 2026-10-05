package dev.varun.forecast.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.varun.forecast.api.service.ForecastGrid;
import dev.varun.forecast.api.service.LookupProgress;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpHeaders;

/**
 * A lookup's progress as server-sent events: a {@code progress} event per report, then
 * {@code done} with the finished grid. A stream that ends without {@code done} failed
 * partway, and the last grid sent is all there is.
 *
 * <p>Started on the first report rather than up front, so a lookup that never reports —
 * a cache hit, a refusal — leaves the response untouched, to be answered as plain JSON
 * with the status code it deserves.
 */
final class GridEvents implements LookupProgress {

    static final String MEDIA_TYPE = "text/event-stream";

    /** Event names. The frontend's {@code requestLookup} matches on exactly these. */
    static final String PROGRESS = "progress";
    static final String DONE = "done";

    private final HttpServletResponse response;
    private final ObjectMapper json;
    private boolean started;
    private boolean visitorLeft;

    GridEvents(HttpServletResponse response, ObjectMapper json) {
        this.response = response;
        this.json = json;
    }

    @Override
    public void filled(ForecastGrid soFar) {
        send(PROGRESS, soFar);
    }

    void done(ForecastGrid grid) {
        send(DONE, grid);
    }

    boolean started() {
        return started;
    }

    private void send(String event, ForecastGrid grid) {
        if (visitorLeft) {
            return;
        }
        try {
            if (!started) {
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType(MEDIA_TYPE);
                response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache");
                started = true;
            }
            // The stream, not the writer: a PrintWriter swallows the IOException that
            // says the visitor has gone.
            ServletOutputStream out = response.getOutputStream();
            out.write(("event: " + event + "\ndata: " + json.writeValueAsString(grid)
                    + "\n\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            // The visitor left. The fill carries on regardless: its samples are the
            // cache the next visitor is served from, and this lookup is already spent.
            visitorLeft = true;
        }
    }
}
