package dev.varun.forecast.api.service;

import dev.varun.forecast.api.config.ForecastProperties;
import dev.varun.forecast.api.repo.IpUsageRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Per-IP daily budgets, one per thing being limited.
 *
 * <p>Lookups and address searches have separate allowances at TomTom, so they get
 * separate budgets here. Sharing one would let a visitor exhaust their route lookups by
 * typing in a search box.
 *
 * <p>The important subtlety, from §9.4: <em>cache hits do not count</em>. Only a request
 * that actually reaches TomTom consumes a token, which is why callers consume explicitly
 * rather than this living in a servlet filter — a filter would charge for requests that
 * cost nothing to serve. A caller that spends and then finds the request never left the
 * process hands the token back with {@link #refund}.
 *
 * <p>Counted in Postgres, not in process: Cloud Run scales to zero and runs up to two
 * instances, and an in-memory count would forget a visitor on the first and count them
 * twice on the second. Budgets refill at the same moment as the global counter, the end
 * of the {@link QuotaDay}.
 */
@Service
public class DailyIpLimiter {

    /** The budgets. An enum rather than a string so a namespace cannot be mistyped. */
    public enum Budget {
        LOOKUP,
        SEARCH
    }

    /**
     * One token spent, and the day it was spent against. A refund goes back to that day
     * rather than to whatever day it is when the refund runs, so a request straddling
     * midnight cannot hand yesterday's token to today.
     */
    public record Spend(LocalDate day, Budget budget, String ip) {}

    private final IpUsageRepository usage;
    private final QuotaDay day;
    private final Map<Budget, Integer> limits = new EnumMap<>(Budget.class);

    public DailyIpLimiter(IpUsageRepository usage, ForecastProperties props, QuotaDay day) {
        this.usage = usage;
        this.day = day;
        limits.put(Budget.LOOKUP, props.lookup().perIpDaily());
        limits.put(Budget.SEARCH, props.search().perIpDaily());
    }

    public int dailyLimit(Budget budget) {
        return limits.get(budget);
    }

    /**
     * Spends one if any are left. The check and the spend are one atomic step, so
     * parallel requests cannot all pass a check and then all spend.
     */
    public Optional<Spend> tryConsume(Budget budget, String ip) {
        LocalDate today = day.today();
        if (!usage.tryIncrement(today, budget.name(), ip, dailyLimit(budget))) {
            return Optional.empty();
        }
        return Optional.of(new Spend(today, budget, ip));
    }

    /** Hands back a token spent on a request that never reached TomTom. */
    public void refund(Spend spend) {
        usage.decrement(spend.day(), spend.budget().name(), spend.ip());
    }

    public long remaining(Budget budget, String ip) {
        return Math.max(0, dailyLimit(budget) - usage.used(day.today(), budget.name(), ip));
    }

    /** When every visitor's budgets refill. */
    public Instant resetsAt() {
        return day.resetsAt();
    }
}
