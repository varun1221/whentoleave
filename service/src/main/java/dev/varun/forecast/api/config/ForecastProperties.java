package dev.varun.forecast.api.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything tunable about cost and freshness in one place, so the numbers that bound
 * spending are visible rather than scattered through the call path.
 */
@ConfigurationProperties(prefix = "forecast")
public record ForecastProperties(Tomtom tomtom, Cache cache, Quota quota, Lookup lookup,
        Search search, KillSwitch killSwitch, Edge edge, Privacy privacy) {

    public record Tomtom(String apiKey, String baseUrl) {
        /** No key configured is a normal state locally: the service serves cache only. */
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /**
     * The Cloudflare edge in front of the API.
     *
     * <p>{@code originSecret} is a header value Cloudflare adds to every request it
     * forwards. When set, an {@code /api/**} request without it is refused, because it
     * did not come through Cloudflare and its {@code CF-Connecting-IP} cannot be trusted.
     * Blank locally, where there is no Cloudflare.
     *
     * <p>{@code allowedOrigins} are the pages that may read API answers from a browser.
     * The site and the API are different hostnames, so this is not optional once the
     * site calls the API directly. Empty locally, where Vite proxies {@code /api} and
     * every call is same-origin.
     */
    public record Edge(String originSecret, List<String> allowedOrigins) {
        public boolean enforced() {
            return originSecret != null && !originSecret.isBlank();
        }
    }

    /**
     * What the per-IP counters are allowed to remember about a visitor.
     *
     * <p>{@code ipSecret} keys the HMAC that stands in for the address. It is
     * configuration rather than a column because a key stored beside the hashes it keys
     * protects nothing. Blank locally, where the counters are throwaway.
     */
    public record Privacy(String ipSecret) {
        public boolean keyed() {
            return ipSecret != null && !ipSecret.isBlank();
        }
    }

    /** A bucket younger than this is served from Postgres without an API call. */
    public record Cache(int ttlDays) {}

    /**
     * How long the kill-switch answer is held in process.
     *
     * <p>Configurable because it trades a database query per request against how long a
     * flip takes to propagate. Zero reads every time, which is what tests want.
     */
    public record KillSwitch(int cacheSeconds) {}

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
    public record Lookup(List<Integer> weekdayHours, int perIpDaily) {}

    /**
     * Address search. Draws on a freemium allowance separate from Routing, so it gets
     * its own per-IP budget rather than sharing the lookup one.
     *
     * <p>{@code minQueryLength} exists because autocomplete fires per keystroke: without
     * it, every "a" a visitor types is a billable request.
     *
     * <p>{@code dailyCeiling} is search's counterpart to the lookup quota: per-IP budgets
     * alone do not bound a caller who controls many addresses.
     */
    public record Search(int perIpDaily, int dailyCeiling, int minQueryLength,
            int resultLimit, String countrySet, int cacheTtlHours) {}
}
