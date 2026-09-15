package dev.varun.forecast.api.web;

import dev.varun.forecast.api.service.ForecastService;
import dev.varun.forecast.api.service.RouteSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RouteController {

    private final ForecastService forecasts;

    public RouteController(ForecastService forecasts) {
        this.forecasts = forecasts;
    }

    /** The seeded corridors. Cheap, cacheable, and never touches the routing API. */
    @GetMapping("/routes")
    public List<RouteSummary> routes() {
        return forecasts.listSeededRoutes();
    }
}
