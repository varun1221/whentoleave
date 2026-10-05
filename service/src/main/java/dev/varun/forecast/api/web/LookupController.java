package dev.varun.forecast.api.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.varun.forecast.api.config.ClientIp;
import dev.varun.forecast.api.service.ForecastGrid;
import dev.varun.forecast.api.service.LookupService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LookupController {

    private static final Logger log = LoggerFactory.getLogger(LookupController.class);

    private final LookupService lookups;
    private final ObjectMapper json;

    public LookupController(LookupService lookups, ObjectMapper json) {
        this.lookups = lookups;
        this.json = json;
    }

    /**
     * An arbitrary corridor. Served from the sample table when it can be, and capped by
     * the daily quota when it cannot.
     *
     * <p>A client that accepts {@code text/event-stream} is shown a cold corridor filling
     * in, as {@link GridEvents}, instead of waiting out every call. Anything answered
     * without fetching is plain JSON whatever the client accepts, so a refusal keeps its
     * status code, and a client that never asked for events never sees one.
     *
     * @return null once the answer has gone out as events
     */
    @PostMapping("/lookup")
    public ResponseEntity<ForecastGrid> lookup(@Valid @RequestBody LookupRequest request,
            HttpServletRequest http, HttpServletResponse response) {
        String accept = http.getHeader(HttpHeaders.ACCEPT);
        if (accept == null || !accept.contains(GridEvents.MEDIA_TYPE)) {
            return ResponseEntity.ok(
                    lookups.lookup(request.origin(), request.dest(), ClientIp.of(http)));
        }
        GridEvents events = new GridEvents(response, json);
        ForecastGrid grid;
        try {
            grid = lookups.lookup(request.origin(), request.dest(), ClientIp.of(http),
                    events);
        } catch (RuntimeException e) {
            if (!events.started()) {
                throw e;
            }
            // Too late for a status code. Ending without "done" is how the page learns
            // the grid it already has is all it will get.
            log.error("lookup failed after streaming began", e);
            return null;
        }
        if (!events.started()) {
            return ResponseEntity.ok(grid);
        }
        events.done(grid);
        return null;
    }
}
