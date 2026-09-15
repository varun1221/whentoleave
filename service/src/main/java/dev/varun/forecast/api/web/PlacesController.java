package dev.varun.forecast.api.web;

import dev.varun.forecast.api.config.ClientIp;
import dev.varun.forecast.api.service.PlaceSuggestion;
import dev.varun.forecast.api.service.PlacesService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/places")
public class PlacesController {

    private final PlacesService places;

    public PlacesController(PlacesService places) {
        this.places = places;
    }

    /**
     * Address suggestions as coordinates. The API key stays server side; the browser
     * only ever sees lat, lon and a label.
     *
     * <p>Debounce this client side — the guards here are a backstop, not a substitute.
     */
    @GetMapping("/autocomplete")
    public List<Suggestion> autocomplete(@RequestParam("q") String query,
            HttpServletRequest http) {
        return places.suggest(query, ClientIp.of(http)).stream()
                .map(s -> new Suggestion(s.coord(), s.lat(), s.lon(), s.description()))
                .toList();
    }

    /**
     * {@code coord} is the string {@code /api/lookup} expects, so the frontend passes it
     * straight through without formatting coordinates itself.
     */
    public record Suggestion(String coord, double lat, double lon, String description) {}
}
