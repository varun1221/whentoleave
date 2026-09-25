package dev.varun.forecast.api.service;

import dev.varun.forecast.api.config.ForecastProperties;
import java.util.List;

/**
 * Properties for unit tests, with the knobs each test needs to turn.
 *
 * <p>One place rather than per-test literals, so adding a config record does not mean
 * editing every test that happens to build one.
 */
public final class TestProps {

    /** Only the presence of a key matters to most tests, never its value. */
    private static final String IP_SECRET = "test-ip-secret";

    public static ForecastProperties with(String baseUrl, String apiKey, int lookupPerIp,
            int searchPerIp, int minQueryLength) {
        return all(baseUrl, apiKey, lookupPerIp, searchPerIp, minQueryLength, 0,
                IP_SECRET);
    }

    public static ForecastProperties defaults() {
        return with("http://localhost:0", "test-key", 5, 20, 3);
    }

    /** For the search tests that exercise the shared daily ceiling. */
    public static ForecastProperties searchCeiling(String baseUrl, int searchPerIp,
            int searchDailyCeiling) {
        return all(baseUrl, "test-key", 5, searchPerIp, searchDailyCeiling, 3, 0,
                IP_SECRET, "");
    }

    /** For the kill-switch tests, which assert on how long an answer is held. */
    public static ForecastProperties killSwitchCaching(int seconds) {
        return all("http://localhost:0", "", 5, 20, 3, seconds, IP_SECRET);
    }

    /** For the IP-hash tests, which need to vary the server secret. */
    public static ForecastProperties ipSecret(String secret) {
        return all("http://localhost:0", "test-key", 5, 20, 3, 0, secret);
    }

    /** An edge secret but no IP secret: the misconfigured deployment, which must fail. */
    public static ForecastProperties deployedWithoutIpSecret() {
        return all("http://localhost:0", "test-key", 5, 20, 1000, 3, 0, "",
                "edge-secret");
    }

    private static ForecastProperties all(String baseUrl, String apiKey, int lookupPerIp,
            int searchPerIp, int minQueryLength, int killSwitchSeconds, String ipSecret) {
        return all(baseUrl, apiKey, lookupPerIp, searchPerIp, 1000, minQueryLength,
                killSwitchSeconds, ipSecret, "");
    }

    private static ForecastProperties all(String baseUrl, String apiKey, int lookupPerIp,
            int searchPerIp, int searchDailyCeiling, int minQueryLength,
            int killSwitchSeconds, String ipSecret, String originSecret) {
        return new ForecastProperties(
                new ForecastProperties.Tomtom(apiKey, baseUrl),
                new ForecastProperties.Cache(7),
                new ForecastProperties.Quota(150),
                new ForecastProperties.Lookup(List.of(6, 7, 8), lookupPerIp),
                new ForecastProperties.Search(searchPerIp, searchDailyCeiling, minQueryLength,
                        5, "US", 24),
                new ForecastProperties.KillSwitch(killSwitchSeconds),
                new ForecastProperties.Edge(originSecret),
                new ForecastProperties.Privacy(ipSecret));
    }

    private TestProps() {}
}
