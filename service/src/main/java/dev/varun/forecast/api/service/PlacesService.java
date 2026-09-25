package dev.varun.forecast.api.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.varun.forecast.api.client.CallNotSentException;
import dev.varun.forecast.api.client.SearchClient;
import dev.varun.forecast.api.config.ForecastProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Address search, wrapped in the three things that keep it free.
 *
 * <p>Autocomplete is the most expensive shape of request in the app: it fires as someone
 * types, so a single search box can generate a dozen requests for one address. Three
 * guards, cheapest first — a length floor, then a cache, then the per-IP budget — so
 * only a genuinely new query from a visitor with headroom ever reaches TomTom.
 */
@Service
public class PlacesService {

    private static final Logger log = LoggerFactory.getLogger(PlacesService.class);

    private final SearchClient search;
    private final DailyIpLimiter perIp;
    private final QuotaDay day;
    private final ForecastProperties props;
    private final Cache<String, List<PlaceSuggestion>> cache;

    public PlacesService(SearchClient search, DailyIpLimiter perIp, QuotaDay day,
            ForecastProperties props) {
        this.search = search;
        this.perIp = perIp;
        this.day = day;
        this.props = props;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofHours(props.search().cacheTtlHours()))
                .maximumSize(10_000)
                .build();
    }

    public List<PlaceSuggestion> suggest(String rawQuery, String clientIp) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.length() < props.search().minQueryLength()) {
            // Not an error: the caller is still typing. An empty list is the honest
            // answer and it costs nothing.
            return List.of();
        }

        // Case and surrounding whitespace do not change what TomTom returns, so they
        // should not produce a second billable request.
        String key = query.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        List<PlaceSuggestion> cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }

        int limit = perIp.dailyLimit(DailyIpLimiter.Budget.SEARCH);
        Optional<DailyIpLimiter.Spend> spend =
                perIp.tryConsume(DailyIpLimiter.Budget.SEARCH, clientIp);
        if (spend.isEmpty()) {
            throw RateLimitedException.used("address searches", limit, day.resetsAt());
        }
        // The visitor is charged first so a rate-limited one never touches the shared
        // total, and refunded if the shared total is what stops them: they should not
        // pay for a limit everyone hit together.
        Optional<DailyIpLimiter.Spend> shared = perIp.tryConsumeShared(
                DailyIpLimiter.Budget.SEARCH, props.search().dailyCeiling());
        if (shared.isEmpty()) {
            perIp.refund(spend.get());
            // A missing dropdown, like any other failed search: the visitor can still
            // paste coordinates.
            log.warn("address search refused: the daily ceiling of {} is spent",
                    props.search().dailyCeiling());
            return List.of();
        }

        try {
            List<PlaceSuggestion> results = search.suggest(query);
            cache.put(key, results);
            return results;
        } catch (CallNotSentException e) {
            // Nothing reached TomTom, so nothing was spent. Only this case is refunded:
            // refunding a request TomTom did receive would let one visitor retry a
            // failing upstream until the shared ceiling, not their own, stopped them.
            perIp.refund(spend.get());
            perIp.refund(shared.get());
            log.warn("address search for \"{}\" not sent: {}", query, e.getMessage());
            return List.of();
        } catch (IOException e) {
            // A failed search is a missing dropdown, not a broken page. The visitor can
            // still paste coordinates, so this degrades rather than propagates.
            log.warn("address search for \"{}\" failed: {}", query, e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
