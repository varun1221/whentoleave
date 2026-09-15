package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.api.DatabaseTest;
import dev.varun.forecast.api.config.ForecastProperties;
import dev.varun.forecast.api.repo.ServiceSettingRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * These build their own KillSwitch with an explicit cache duration, so the caching
 * behaviour itself can be asserted rather than worked around. The bean in the Spring
 * context reads a configured duration, which tests set to zero.
 */
class KillSwitchTest extends DatabaseTest {

    @Autowired
    private ServiceSettingRepository settings;

    /** Reads the database every time. */
    private KillSwitch freshSwitch() {
        return switchCaching(0);
    }

    private KillSwitch switchCaching(int seconds) {
        return new KillSwitch(settings, new ForecastProperties(
                new ForecastProperties.Tomtom("", "http://localhost:0"),
                new ForecastProperties.Cache(7),
                new ForecastProperties.Quota(150),
                new ForecastProperties.Lookup(java.util.List.of(6, 7, 8), 5),
                new ForecastProperties.Search(20, 3, 5, "US", 24),
                new ForecastProperties.KillSwitch(seconds),
                new ForecastProperties.Edge("")));
    }

    private void setSetting(String value) {
        jdbc.update("UPDATE service_setting SET value = ? WHERE key = 'lookups_enabled'",
                value);
    }

    @Test
    void enabledWhenTheRowSaysTrue() {
        setSetting("true");
        assertTrue(freshSwitch().lookupsEnabled());
    }

    @Test
    void pausedWhenTheRowSaysFalse() {
        setSetting("false");
        assertFalse(freshSwitch().lookupsEnabled());
    }

    /** The documented cost of not querying per request: a flip is not instant. */
    @Test
    void cachesWithinItsTtlSoAFlipIsNotInstant() {
        setSetting("true");
        KillSwitch killSwitch = switchCaching(300);
        assertTrue(killSwitch.lookupsEnabled());

        setSetting("false");

        assertTrue(killSwitch.lookupsEnabled(),
                "the same instance should still serve its cached answer");
        assertFalse(freshSwitch().lookupsEnabled(),
                "an instance with no cache should see the change");
    }

    /** With caching off, a flip is visible on the next call. */
    @Test
    void withNoCachingAFlipIsImmediate() {
        setSetting("true");
        KillSwitch killSwitch = freshSwitch();
        assertTrue(killSwitch.lookupsEnabled());

        setSetting("false");

        assertFalse(killSwitch.lookupsEnabled());
    }

    /**
     * Fails closed. For a cost control, no explicit permission should read as denial
     * rather than as a default-on.
     */
    @Test
    void aMissingRowMeansPaused() {
        jdbc.execute("DELETE FROM service_setting WHERE key = 'lookups_enabled'");

        assertFalse(freshSwitch().lookupsEnabled());

        // Put it back for whatever runs next.
        jdbc.update("INSERT INTO service_setting (key, value) "
                + "VALUES ('lookups_enabled', 'true')");
    }

    @Test
    void anUnparseableValueIsTreatedAsPaused() {
        setSetting("maybe");
        assertFalse(freshSwitch().lookupsEnabled());
    }
}
