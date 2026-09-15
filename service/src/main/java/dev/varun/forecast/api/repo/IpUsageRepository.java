package dev.varun.forecast.api.repo;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Per-IP usage counts. Plain JDBC rather than Spring Data because there is no entity
 * worth mapping: the only operations are a conditional upsert, a decrement and a read.
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
    public boolean tryIncrement(LocalDate day, String budget, String ip, int limit) {
        if (limit <= 0) {
            return false;
        }
        int rows = jdbc.update("""
                INSERT INTO ip_daily_usage (day, budget, ip, used)
                VALUES (:day, :budget, :ip, 1)
                ON CONFLICT (day, budget, ip)
                DO UPDATE SET used = ip_daily_usage.used + 1
                WHERE ip_daily_usage.used < :limit
                """, params(day, budget, ip).addValue("limit", limit));
        return rows == 1;
    }

    /** Gives one unit back, never going below zero. */
    public void decrement(LocalDate day, String budget, String ip) {
        jdbc.update("""
                UPDATE ip_daily_usage SET used = used - 1
                WHERE day = :day AND budget = :budget AND ip = :ip AND used > 0
                """, params(day, budget, ip));
    }

    public int used(LocalDate day, String budget, String ip) {
        List<Integer> used = jdbc.queryForList("""
                SELECT used FROM ip_daily_usage
                WHERE day = :day AND budget = :budget AND ip = :ip
                """, params(day, budget, ip), Integer.class);
        return used.isEmpty() ? 0 : used.get(0);
    }

    private static MapSqlParameterSource params(LocalDate day, String budget, String ip) {
        return new MapSqlParameterSource(Map.of("day", day, "budget", budget, "ip", ip));
    }
}
