package dev.varun.forecast.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.regex.Pattern;

/**
 * One seeded corridor from config/routes.json.
 *
 * <p>{@code origin} and {@code dest} are {@code "lat,lon"} — the format TomTom's
 * calculateRoute path takes, and the format Google Maps hands you when you right-click a
 * point and copy the coordinates. No place-ID lookup needed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Route(String id, String name, String origin, String dest) {

    private static final Pattern LAT_LON =
            Pattern.compile("-?\\d{1,3}(\\.\\d+)?,-?\\d{1,3}(\\.\\d+)?");

    public boolean isConfigured() {
        return isCoordinate(origin) && isCoordinate(dest);
    }

    private static boolean isCoordinate(String value) {
        return value != null && LAT_LON.matcher(value.replace(" ", "")).matches();
    }

    /** Whitespace-tolerant: "37.3352, -121.8811" pastes straight out of a map. */
    public Route {
        origin = origin == null ? null : origin.replace(" ", "");
        dest = dest == null ? null : dest.replace(" ", "");
    }
}
