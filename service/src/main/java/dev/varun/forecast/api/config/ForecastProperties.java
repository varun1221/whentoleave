package dev.varun.forecast.api.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything tunable about cost and freshness in one place, so the numbers that bound
 * spending are visible rather than scattered through the call path.
 */
@ConfigurationProperties(prefix = "forecast")
public record ForecastProperties(Tomtom tomtom, Cache cache, Quota quota, Lookup lookup) {

    public record Tomtom(String apiKey, String baseUrl) {
        /** No key configured is a normal state locally: the service serves cache only. */
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /** A bucket younger than this is served from Postgres without an API call. */
    public record Cache(int ttlDays) {}

    /**
     * The application-level hard stop. TomTom freemium has no billing cap to fall back
     * on, so this counter is what makes a public endpoint safe to expose.
     */
    public record Quota(int dailyCeiling) {}

    /**
     * A cold user corridor would need 91 calls for a full grid, which blows past the
     * daily ceiling on the second lookup. User lookups therefore get a reduced grid:
     * weekdays only, the morning and evening peaks. 5 days x 9 hours = 45 calls.
     * Seeded corridors keep the full 7 x 13.
     */
    public record Lookup(List<Integer> weekdayHours) {}
}
