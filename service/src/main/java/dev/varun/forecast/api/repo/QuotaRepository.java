package dev.varun.forecast.api.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;

/**
 * The daily call counter. Native SQL rather than an entity because the only operations
 * that matter are an upsert and a locked read, and both are clearer as SQL.
 */
public interface QuotaRepository extends JpaRepository<DailyQuota, LocalDate> {

    /** Creates today's row if it is not there yet. Safe to call concurrently. */
    @Modifying
    @Query(value = """
            INSERT INTO daily_quota (day, calls_made) VALUES (:day, 0)
            ON CONFLICT (day) DO NOTHING
            """, nativeQuery = true)
    void ensureRow(@Param("day") LocalDate day);

    /**
     * Reads today's count and holds the row until the surrounding transaction commits,
     * so two concurrent lookups cannot both see the same headroom and both spend it.
     */
    @Query(value = "SELECT calls_made FROM daily_quota WHERE day = :day FOR UPDATE",
            nativeQuery = true)
    Integer lockAndRead(@Param("day") LocalDate day);

    @Modifying
    @Query(value = "UPDATE daily_quota SET calls_made = :calls WHERE day = :day",
            nativeQuery = true)
    void setCalls(@Param("day") LocalDate day, @Param("calls") int calls);

    /** Gives back unspent calls, never taking the count below zero. */
    @Modifying
    @Query(value = """
            UPDATE daily_quota SET calls_made = GREATEST(0, calls_made - :calls)
            WHERE day = :day
            """, nativeQuery = true)
    void release(@Param("day") LocalDate day, @Param("calls") int calls);
}
