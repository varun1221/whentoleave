package dev.varun.forecast.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.varun.forecast.api.DatabaseTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The counter that makes a public endpoint safe to expose. TomTom freemium has no
 * payment method behind it, so there is no vendor-side cap to fall back on: an error
 * here is the difference between a bounded service and an unbounded one.
 */
class QuotaServiceTest extends DatabaseTest {

    /** Matches forecast.quota.daily-ceiling in the test configuration. */
    private static final int CEILING = 150;

    @Autowired
    private QuotaService quotas;

    @Autowired
    private QuotaDay day;

    @Test
    void startsWithTheWholeBudgetAvailable() {
        assertEquals(CEILING, quotas.remaining());
    }

    @Test
    void reservingSpendsFromTheBudget() {
        assertEquals(10, quotas.reserve(10).granted());
        assertEquals(CEILING - 10, quotas.remaining());
    }

    @Test
    void createsTodaysRowOnFirstUse() {
        quotas.reserve(1);

        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM daily_quota", Integer.class));
    }

    /**
     * Partial grants matter: a lookup asking for 45 slots with 10 left should get 10 and
     * return a partial grid, not be refused outright.
     */
    @Test
    void grantsWhatIsLeftRatherThanRefusingOutright() {
        quotas.reserve(CEILING - 10);

        assertEquals(10, quotas.reserve(45).granted());
        assertEquals(0, quotas.remaining());
    }

    @Test
    void grantsNothingOnceExhausted() {
        quotas.reserve(CEILING);

        assertEquals(0, quotas.reserve(1).granted());
        assertEquals(0, quotas.remaining());
    }

    @Test
    void neverGrantsMoreThanTheCeilingEvenWhenAskedFor() {
        assertEquals(CEILING, quotas.reserve(CEILING + 500).granted());
    }

    @Test
    void zeroAndNegativeRequestsAreNoOps() {
        assertEquals(0, quotas.reserve(0).granted());
        assertEquals(0, quotas.reserve(-5).granted());
        assertEquals(CEILING, quotas.remaining());
    }

    /**
     * The reason the reserve takes SELECT ... FOR UPDATE. Without the row lock, two
     * concurrent lookups both read the same headroom and both spend it, and the ceiling
     * is quietly not a ceiling.
     */
    @Test
    void concurrentReservesNeverExceedTheCeiling() throws Exception {
        int threads = 200;
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Callable<Integer>> jobs = IntStream.range(0, threads)
                    .<Callable<Integer>>mapToObj(i -> () -> quotas.reserve(1).granted())
                    .toList();

            int totalGranted = 0;
            for (Future<Integer> future : pool.invokeAll(jobs)) {
                totalGranted += future.get();
            }

            assertEquals(CEILING, totalGranted,
                    "exactly the ceiling should be handed out, no more and no less");
            assertEquals(CEILING, jdbc.queryForObject(
                    "SELECT calls_made FROM daily_quota", Integer.class));
            assertEquals(0, quotas.remaining());
        }
    }

    @Test
    void remainingNeverGoesNegative() {
        quotas.reserve(CEILING);
        jdbc.update("UPDATE daily_quota SET calls_made = calls_made + 50");

        assertTrue(quotas.remaining() >= 0);
        assertEquals(0, quotas.remaining());
    }

    /** Calls reserved but never sent to TomTom go back into the budget. */
    @Test
    void releasingReturnsUnsentCallsToTheBudget() {
        QuotaService.Reservation reservation = quotas.reserve(45);

        quotas.release(reservation, 45);

        assertEquals(CEILING, quotas.remaining());
    }

    @Test
    void releasingNeverTakesTheCountBelowZero() {
        QuotaService.Reservation reservation = quotas.reserve(5);

        quotas.release(reservation, 50);

        assertEquals(CEILING, quotas.remaining());
        assertEquals(0, jdbc.queryForObject(
                "SELECT calls_made FROM daily_quota", Integer.class));
    }

    /**
     * The global counter and the per-IP one share a day, so the UI has one reset time.
     * Only discriminating between 17:00 and midnight Pacific, when UTC has already
     * rolled over; {@link QuotaDayTest} pins the boundary itself at a fixed clock.
     */
    @Test
    void countsAgainstThePacificDay() {
        quotas.reserve(1);

        assertEquals(LocalDate.now(ZoneId.of("America/Los_Angeles")), jdbc.queryForObject(
                "SELECT day FROM daily_quota", LocalDate.class));
    }

    /**
     * A lookup that reserves before midnight and releases after must release into the
     * day it reserved from. Releasing into the new day would let that day's calls pass
     * the ceiling by whatever was released.
     */
    @Test
    void aReleaseAfterMidnightGoesBackToTheDayItWasReservedFrom() {
        LocalDate yesterday = LocalDate.now(ZoneId.of("America/Los_Angeles")).minusDays(1);
        jdbc.update("INSERT INTO daily_quota (day, calls_made) VALUES (?, 45)", yesterday);
        quotas.reserve(10);

        quotas.release(new QuotaService.Reservation(yesterday, 45), 45);

        assertEquals(CEILING - 10, quotas.remaining(), "today's count is untouched");
        assertEquals(0, jdbc.queryForObject(
                "SELECT calls_made FROM daily_quota WHERE day = ?", Integer.class, yesterday));
    }

    @Test
    void reportsWhenTheBudgetRefills() {
        assertEquals(nextPacificMidnight(), day.resetsAt());
    }

    private static Instant nextPacificMidnight() {
        ZoneId pacific = ZoneId.of("America/Los_Angeles");
        return LocalDate.now(pacific).plusDays(1).atStartOfDay(pacific).toInstant();
    }

    @Test
    void reportsTheConfiguredCeiling() {
        assertEquals(CEILING, quotas.dailyCeiling());
    }
}
