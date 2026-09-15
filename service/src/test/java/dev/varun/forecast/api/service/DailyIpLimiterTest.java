package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.api.DatabaseTest;
import dev.varun.forecast.api.repo.IpUsageRepository;
import dev.varun.forecast.api.service.DailyIpLimiter.Budget;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Per-IP budgets live in Postgres, not in process: Cloud Run scales to zero and runs up
 * to two instances, and an in-memory bucket would forget a visitor on the first and
 * count them twice on the second.
 */
class DailyIpLimiterTest extends DatabaseTest {

    private static final Instant NOON_PDT = Instant.parse("2026-09-15T19:00:00Z");

    @Autowired
    private IpUsageRepository usage;

    private DailyIpLimiter limiter(int lookupPerIp, int searchPerIp) {
        return limiterAt(NOON_PDT, lookupPerIp, searchPerIp);
    }

    private DailyIpLimiter limiterAt(Instant now, int lookupPerIp, int searchPerIp) {
        return new DailyIpLimiter(usage,
                TestProps.with("http://localhost:0", "k", lookupPerIp, searchPerIp, 3),
                new QuotaDay(Clock.fixed(now, ZoneOffset.UTC)));
    }

    @Test
    void capacityComesFromConfiguration() {
        assertEquals(3, limiter(3, 9).dailyLimit(Budget.LOOKUP));
        assertEquals(9, limiter(3, 9).dailyLimit(Budget.SEARCH));
    }

    @Test
    void spendsDownToZeroThenRefuses() {
        DailyIpLimiter limiter = limiter(3, 20);
        assertTrue(limiter.tryConsume(Budget.LOOKUP, "1.2.3.4").isPresent());
        assertTrue(limiter.tryConsume(Budget.LOOKUP, "1.2.3.4").isPresent());
        assertTrue(limiter.tryConsume(Budget.LOOKUP, "1.2.3.4").isPresent());
        assertFalse(limiter.tryConsume(Budget.LOOKUP, "1.2.3.4").isPresent());
        assertEquals(0, limiter.remaining(Budget.LOOKUP, "1.2.3.4"));
    }

    @Test
    void oneVisitorRunningOutDoesNotAffectAnother() {
        DailyIpLimiter limiter = limiter(1, 20);
        limiter.tryConsume(Budget.LOOKUP, "1.2.3.4");

        assertEquals(0, limiter.remaining(Budget.LOOKUP, "1.2.3.4"));
        assertEquals(1, limiter.remaining(Budget.LOOKUP, "5.6.7.8"));
    }

    /**
     * Routing and Search have separate allowances at TomTom. Sharing a bucket would let
     * someone exhaust their route lookups by typing in a search box.
     */
    @Test
    void budgetsAreIndependentForTheSameVisitor() {
        DailyIpLimiter limiter = limiter(1, 20);
        limiter.tryConsume(Budget.LOOKUP, "1.2.3.4");

        assertEquals(0, limiter.remaining(Budget.LOOKUP, "1.2.3.4"));
        assertEquals(20, limiter.remaining(Budget.SEARCH, "1.2.3.4"));
    }

    /**
     * The bug the in-memory version had: a check followed by a spend let parallel
     * requests from one IP all pass the check. The spend itself has to be the check.
     */
    @Test
    void concurrentRequestsFromOneVisitorNeverExceedTheirLimit() throws Exception {
        DailyIpLimiter limiter = limiter(5, 20);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Callable<Optional<DailyIpLimiter.Spend>>> jobs = IntStream.range(0, 50)
                    .<Callable<Optional<DailyIpLimiter.Spend>>>mapToObj(
                            i -> () -> limiter.tryConsume(Budget.LOOKUP, "1.2.3.4"))
                    .toList();

            int granted = 0;
            for (Future<Optional<DailyIpLimiter.Spend>> future : pool.invokeAll(jobs)) {
                if (future.get().isPresent()) {
                    granted++;
                }
            }

            assertEquals(5, granted, "exactly the limit, no more and no less");
            assertEquals(0, limiter.remaining(Budget.LOOKUP, "1.2.3.4"));
        }
    }

    /** Two instances share one count, because the count is not in either of them. */
    @Test
    void separateInstancesShareOneCount() {
        limiter(2, 20).tryConsume(Budget.LOOKUP, "1.2.3.4");
        limiter(2, 20).tryConsume(Budget.LOOKUP, "1.2.3.4");

        assertFalse(limiter(2, 20).tryConsume(Budget.LOOKUP, "1.2.3.4").isPresent());
    }

    /** A spend for a request that never reached TomTom is handed back. */
    @Test
    void aRefundReturnsOneToken() {
        DailyIpLimiter limiter = limiter(2, 20);
        limiter.tryConsume(Budget.LOOKUP, "1.2.3.4");
        DailyIpLimiter.Spend spend =
                limiter.tryConsume(Budget.LOOKUP, "1.2.3.4").orElseThrow();

        limiter.refund(spend);

        assertEquals(1, limiter.remaining(Budget.LOOKUP, "1.2.3.4"));
    }

    @Test
    void refundingMoreThanWasSpentDoesNotAddHeadroom() {
        DailyIpLimiter limiter = limiter(2, 20);
        DailyIpLimiter.Spend spend =
                limiter.tryConsume(Budget.LOOKUP, "1.2.3.4").orElseThrow();

        limiter.refund(spend);
        limiter.refund(spend);

        assertEquals(2, limiter.remaining(Budget.LOOKUP, "1.2.3.4"));
    }

    /**
     * A lookup that spends at 23:59 and refunds after midnight must hand the token back
     * to the day it came from. Refunding into the new day would give that visitor more
     * than their limit for it.
     */
    @Test
    void aRefundAfterMidnightGoesBackToTheDayItWasSpentOn() {
        Instant lateEvening = Instant.parse("2026-09-16T06:59:00Z"); // 23:59 PDT
        Instant afterMidnight = lateEvening.plus(Duration.ofMinutes(2));
        DailyIpLimiter.Spend spend = limiterAt(lateEvening, 2, 20)
                .tryConsume(Budget.LOOKUP, "1.2.3.4").orElseThrow();
        limiterAt(afterMidnight, 2, 20).tryConsume(Budget.LOOKUP, "1.2.3.4");

        limiterAt(afterMidnight, 2, 20).refund(spend);

        assertEquals(1, limiterAt(afterMidnight, 2, 20).remaining(Budget.LOOKUP, "1.2.3.4"),
                "the new day's spend is untouched");
        assertEquals(2, limiterAt(lateEvening, 2, 20).remaining(Budget.LOOKUP, "1.2.3.4"),
                "the old day's spend was handed back");
    }

    /** Every visitor refills at the same moment: Pacific midnight. */
    @Test
    void theBudgetRefillsAtPacificMidnight() {
        Instant lateEvening = Instant.parse("2026-09-16T06:59:00Z"); // 23:59 PDT
        limiterAt(lateEvening, 1, 20).tryConsume(Budget.LOOKUP, "1.2.3.4");
        assertEquals(0, limiterAt(lateEvening, 1, 20).remaining(Budget.LOOKUP, "1.2.3.4"));

        Instant afterMidnight = lateEvening.plus(Duration.ofMinutes(2));
        assertEquals(1, limiterAt(afterMidnight, 1, 20).remaining(Budget.LOOKUP, "1.2.3.4"));
    }
}
