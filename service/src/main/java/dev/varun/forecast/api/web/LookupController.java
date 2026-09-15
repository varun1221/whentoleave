package dev.varun.forecast.api.web;

import dev.varun.forecast.api.service.ForecastGrid;
import dev.varun.forecast.api.service.LookupService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LookupController {

    private final LookupService lookups;

    public LookupController(LookupService lookups) {
        this.lookups = lookups;
    }

    /**
     * An arbitrary corridor. Served from the sample table when it can be, and capped by
     * the daily quota when it cannot.
     */
    @PostMapping("/lookup")
    public ForecastGrid lookup(@Valid @RequestBody LookupRequest request) {
        return lookups.lookup(request.origin(), request.dest());
    }
}
