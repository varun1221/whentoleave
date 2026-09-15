package dev.varun.forecast.api.service;

import dev.varun.forecast.api.config.ForecastProperties;
import java.util.List;

/** Properties for unit tests, with the knobs each test needs to turn. */
public final class TestProps {

    public static ForecastProperties with(String baseUrl, String apiKey, int lookupPerIp,
            int searchPerIp, int minQueryLength) {
        return new ForecastProperties(
                new ForecastProperties.Tomtom(apiKey, baseUrl),
                new ForecastProperties.Cache(7),
                new ForecastProperties.Quota(150),
                new ForecastProperties.Lookup(List.of(6, 7, 8), lookupPerIp),
                new ForecastProperties.Search(searchPerIp, minQueryLength, 5, "US", 24),
                new ForecastProperties.KillSwitch(0),
                new ForecastProperties.Edge(""));
    }

    public static ForecastProperties defaults() {
        return with("http://localhost:0", "test-key", 5, 20, 3);
    }

    private TestProps() {}
}
