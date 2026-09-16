package dev.varun.forecast.api.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Per-IP usage counts. Plain JDBC rather than Spring Data because there is no entity
 * worth mapping: the only operations are a conditional upsert, a decrement, a read and
 * a prune.
 *
 * <p>Visitors arrive here already reduced to a keyed hash — see {@code IpHasher}. This
 * class never sees an address, which is the point.
 */
@Repository
public class IpUsageRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IpUsageRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Spends one unit if fewer than {@code limit} are spent, in a single statement.
     *
     * <p>The check and the spend are the same statement on purpose. Reading the count
     * and then writing it lets parallel requests all read the same headroom; here the
     * {@code WHERE} on the conflict branch is evaluated against the locked row, so the
     * count can never pass the limit however many requests arrive at once.
     *
     * @return whether a unit was spent
     */
    public boolean tryIncrement(LocalDate day, String budget, String ipHash, int limit) {
        if (limit <= 0) {
            return false;
        }
        int rows = jdbc.update("""
                INSERT INTO ip_daily_usage (day, budget, ip_hash, used)
                VALUES (:day, :budget, :ipHash, 1)
                ON CONFLICT (day, budget, ip_hash)
                DO UPDATE SET used = ip_daily_usage.used + 1
                WHERE ip_daily_usage.used < :limit
                """, params(day, budget, ipHash).addValue("limit", limit));
        return rows == 1;
    }

    /** Gives one unit back, never going below zero. */
    public void decrement(LocalDate day, String budget, String ipHash) {
        jdbc.update("""
                UPDATE ip_daily_usage SET used = used - 1
                WHERE day = :day AND budget = :budget AND ip_hash = :ipHash AND used > 0
                """, params(day, budget, ipHash));
    }

    public int used(LocalDate day, String budget, String ipHash) {
        List<Integer> used = jdbc.queryForList("""
                SELECT used FROM ip_daily_usage
                WHERE day = :day AND budget = :budget AND ip_hash = :ipHash
                """, params(day, budget, ipHash), Integer.class);
        return used.isEmpty() ? 0 : used.get(0);
    }

    /**
     * Drops counters for days that are over. The primary key leads with {@code day}, so
     * this uses its index rather than scanning.
     *
     * @return how many rows went
     */
    public int deleteBefore(LocalDate cutoff) {
        return jdbc.update("DELETE FROM ip_daily_usage WHERE day < :cutoff",
                new MapSqlParameterSource("cutoff", cutoff));
    }

    private static MapSqlParameterSource params(LocalDate day, String budget,
            String ipHash) {
        return new MapSqlParameterSource(
                Map.of("day", day, "budget", budget, "ipHash", ipHash));
    }
}
