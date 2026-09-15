package dev.varun.forecast.api.service;

/**
 * One address search result.
 *
 * <p>Coordinates rather than a place ID, because coordinates are what calculateRoute
 * consumes. Returning an opaque ID would force a second server round trip to resolve it
 * before a route could be computed.
 */
public record PlaceSuggestion(double lat, double lon, String description) {

    /** The form the lookup endpoint accepts, so the frontend never formats this itself. */
    public String coord() {
        return Coordinates.normalise(lat + "," + lon);
    }
}
