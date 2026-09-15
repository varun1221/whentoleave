package dev.varun.forecast.api.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * {@code POST /api/lookup} body. Coordinates, not place IDs, because coordinates are
 * what calculateRoute consumes.
 */
public record LookupRequest(
        @NotBlank
        @Pattern(regexp = COORD, message = "Expected \"lat,lon\"")
        String origin,

        @NotBlank
        @Pattern(regexp = COORD, message = "Expected \"lat,lon\"")
        String dest) {

    /** A coordinate pair, tolerating the space a map paste leaves behind. */
    static final String COORD = "^-?\\d{1,3}(\\.\\d+)?,\\s*-?\\d{1,3}(\\.\\d+)?$";
}
