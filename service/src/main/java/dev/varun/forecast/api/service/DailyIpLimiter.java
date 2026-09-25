package dev.varun.forecast.api.service;

import dev.varun.forecast.api.config.ForecastProperties;
import dev.varun.forecast.api.repo.IpUsageRepository;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
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

    private static final Logger log = LoggerFactory.getLogger(DailyIpLimiter.class);

    /** The budgets. An enum rather than a string so a namespace cannot be mistyped. */
    public enum Budget {
        LOOKUP,
        SEARCH
    }

    /**
     * One token spent, and the day it was spent against. A refund goes back to that day
     * rather than to whatever day it is when the refund runs, so a request straddling
     * midnight cannot hand yesterday's token to today.
     *
     * <p>Carries the hashed visitor key, not the address: once a request is past
     * {@link IpHasher} the address does not travel any further.
     */
    public record Spend(LocalDate day, Budget budget, String ipHash) {}

    /** The row that counts {@link #tryConsumeShared}; see there for why it is safe. */
    private static final String SHARED = "*";

    private final IpUsageRepository usage;
    private final QuotaDay day;
    private final IpHasher hasher;
    private final Map<Budget, Integer> limits = new EnumMap<>(Budget.class);

    /** The last day this instance pruned for; see {@link #prune}. */
    private LocalDate prunedFor;

    public DailyIpLimiter(IpUsageRepository usage, ForecastProperties props, QuotaDay day,
            IpHasher hasher) {
        this.usage = usage;
        this.day = day;
        this.hasher = hasher;
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
        prune(today);
        String ipHash = hasher.hash(ip);
        if (!usage.tryIncrement(today, budget.name(), ipHash, dailyLimit(budget))) {
            return Optional.empty();
        }
        return Optional.of(new Spend(today, budget, ipHash));
    }

    /**
     * Spends one against a budget's day-wide total, across every visitor, if fewer than
     * {@code ceiling} are spent.
     *
     * <p>Per-IP budgets do not bound a caller who holds many addresses, so a budget with
     * no other global cap needs this as well. Counted in the same table and the same
     * atomic statement as the per-IP budgets, under a key no {@link IpHasher} output can
     * collide with: a hash is base64url, which has no {@code *}.
     */
    public Optional<Spend> tryConsumeShared(Budget budget, int ceiling) {
        LocalDate today = day.today();
        prune(today);
        if (!usage.tryIncrement(today, budget.name(), SHARED, ceiling)) {
            return Optional.empty();
        }
        return Optional.of(new Spend(today, budget, SHARED));
    }

    /** Hands back a token spent on a request that never reached TomTom. */
    public void refund(Spend spend) {
        usage.decrement(spend.day(), spend.budget().name(), spend.ipHash());
    }

    public long remaining(Budget budget, String ip) {
        return Math.max(0,
                dailyLimit(budget) - usage.used(day.today(), budget.name(),
                        hasher.hash(ip)));
    }

    /**
     * Drops the counters for days that are over, once per instance per day.
     *
     * <p>On a request rather than on a timer because Cloud Run scales to zero: a request
     * is guaranteed to arrive eventually, a scheduled tick on a stopped instance is not.
     * One extra DELETE on the first request after midnight is a price worth paying to
     * not need a scheduler that only works while someone is already awake.
     *
     * <p>Yesterday is kept. A lookup that spends at 23:59 refunds against {@link Spend}'s
     * day, and that refund can land after midnight; deleting the row first would silently
     * drop it.
     *
     * <p>Housekeeping never fails a request: a prune that cannot run is logged and the
     * request carries on. Only a prune that succeeded is remembered, so a failing one is
     * retried on the next request rather than leaving the table to grow until tomorrow —
     * unbounded growth is the thing this exists to prevent.
     */
    private synchronized void prune(LocalDate today) {
        if (today.equals(prunedFor)) {
            return;
        }
        LocalDate cutoff = today.minusDays(1);
        try {
            int dropped = usage.deleteBefore(cutoff);
            prunedFor = today;
            if (dropped > 0) {
                log.info("pruned {} ip_daily_usage rows from before {}", dropped, cutoff);
            }
        } catch (DataAccessException e) {
            log.warn("could not prune ip_daily_usage: {}", e.getMessage());
        }
    }
}
