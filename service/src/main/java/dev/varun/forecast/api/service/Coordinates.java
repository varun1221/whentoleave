package dev.varun.forecast.api.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Coordinate parsing and normalisation.
 *
 * <p>Normalisation is a cost control, not tidiness. Corridor identity is the coordinate
 * string, so "37.3352,-121.8811" and "37.33520, -121.88110" would otherwise be two
 * corridors, each costing its own 45 calls to fill. Rounding to four decimal places —
 * about 11 metres — collapses the near-duplicates that a map click or a search result
 * produces without merging genuinely different addresses.
 */
public final class Coordinates {

    /** Four decimals is roughly 11 m at these latitudes. */
    private static final int PRECISION = 4;

    public static String normalise(String raw) {
        String[] parts = raw.trim().split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected \"lat,lon\", got: " + raw);
        }
        BigDecimal lat = round(parts[0], -90, 90, "latitude");
        BigDecimal lon = round(parts[1], -180, 180, "longitude");
        return lat.toPlainString() + "," + lon.toPlainString();
    }

    private static BigDecimal round(String raw, int min, int max, String name) {
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number for " + name + ": " + raw);
        }
        if (value.doubleValue() < min || value.doubleValue() > max) {
            throw new IllegalArgumentException(
                    name + " out of range (" + min + ".." + max + "): " + raw);
        }
        return value.setScale(PRECISION, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private Coordinates() {}
}
