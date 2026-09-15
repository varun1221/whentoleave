package dev.varun.forecast.api.web;

import dev.varun.forecast.api.service.ForecastService;
import dev.varun.forecast.api.service.RouteSummary;
import dev.varun.forecast.api.service.ForecastGrid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /** The full day x hour grid for one seeded corridor. Never costs an API call. */
    @GetMapping("/routes/{id}/forecast")
    public ResponseEntity<ForecastGrid> forecast(@PathVariable String id) {
        return forecasts.forecastFor(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
