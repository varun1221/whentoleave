package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CoordinatesTest {

    @Test
    void stripsTheSpaceAMapPasteLeavesBehind() {
        assertEquals("37.3352,-121.8811", Coordinates.normalise("37.3352, -121.8811"));
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertEquals("37.3352,-121.8811", Coordinates.normalise("  37.3352,-121.8811 "));
    }

    /**
     * The point of normalising at all: these are the same corner, and without this they
     * would be two corridors, each paying its own 45 calls to fill the same road.
     */
    @Test
    void trailingZerosDoNotCreateASecondCorridor() {
        assertEquals(
                Coordinates.normalise("37.3352,-121.8811"),
                Coordinates.normalise("37.33520, -121.88110"));
    }

    @Test
    void roundsToFourDecimalsSoNearIdenticalPointsCollapse() {
        // ~1 m apart. Not worth a second grid.
        assertEquals(
                Coordinates.normalise("37.33521,-121.88113"),
                Coordinates.normalise("37.33519,-121.88108"));
    }

    @Test
    void keepsGenuinelyDifferentPlacesApart() {
        // ~30 m apart, which survives rounding.
        org.junit.jupiter.api.Assertions.assertNotEquals(
                Coordinates.normalise("37.3352,-121.8811"),
                Coordinates.normalise("37.3355,-121.8814"));
    }

    @Test
    void integerCoordinatesKeepAFormTheLookupPatternAccepts() {
        String normalised = Coordinates.normalise("37.0,-121.0");
        assertEquals("37,-121", normalised);
        org.junit.jupiter.api.Assertions.assertTrue(
                normalised.matches("^-?\\d{1,3}(\\.\\d+)?,\\s*-?\\d{1,3}(\\.\\d+)?$"),
                "normalised output must still satisfy the LookupRequest pattern");
    }

    @Test
    void rejectsAMissingHalf() {
        assertThrows(IllegalArgumentException.class,
                () -> Coordinates.normalise("37.3352"));
    }

    @Test
    void rejectsNonNumbers() {
        assertThrows(IllegalArgumentException.class,
                () -> Coordinates.normalise("here,there"));
    }

    @Test
    void rejectsOutOfRangeLatitude() {
        assertThrows(IllegalArgumentException.class,
                () -> Coordinates.normalise("91.0,-121.8811"));
    }

    @Test
    void rejectsOutOfRangeLongitude() {
        assertThrows(IllegalArgumentException.class,
                () -> Coordinates.normalise("37.3352,-181.0"));
    }
}
