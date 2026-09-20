package dev.varun.forecast.api.config;

import dev.varun.forecast.api.service.ForecastGrid;
import dev.varun.forecast.api.service.RouteSummary;
import dev.varun.forecast.api.web.ApiError;
import dev.varun.forecast.api.web.LookupRequest;
import dev.varun.forecast.api.web.PlacesController;
import dev.varun.forecast.api.web.QuotaController;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.context.annotation.Configuration;

/**
 * Every type Jackson has to reach by reflection in a native image.
 *
 * <p>A native image keeps only what the build could prove reachable, and a record that is
 * never mentioned outside a controller's return type is not provable: the closed world
 * cannot see that Jackson will ask for its components at runtime. Left out, each one
 * fails the same way — an empty <code>{}</code> body, HTTP 200, no exception — which is
 * the failure mode worth spending an explicit list to avoid.
 *
 * <p>The TomTom clients are deliberately absent. Both parse with {@code readTree} into a
 * {@code JsonNode} rather than binding to DTOs, so there is nothing for Jackson to
 * reflect on; §9.6's warning about fighting reflection config mostly does not apply to
 * them.
 */
@Configuration(proxyBeanMethods = false)
@RegisterReflectionForBinding({
    // Responses.
    ForecastGrid.class,
    ForecastGrid.Bucket.class,
    RouteSummary.class,
    QuotaController.QuotaStatus.class,
    PlacesController.Suggestion.class,
    // Sent on every refusal, by the exception handler rather than by a controller, which
    // is exactly the path least likely to be exercised before a deploy.
    ApiError.class,
    // The one inbound body. Bean Validation reads its constraints reflectively too.
    LookupRequest.class,
})
class NativeHints {}
