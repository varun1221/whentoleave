package dev.varun.forecast.api.repo;

import dev.varun.forecast.api.domain.Sample;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface SampleRepository extends JpaRepository<Sample, Long> {

    /**
     * One row per corridor rather than a count query per corridor. With five seeded
     * corridors the difference is academic; with user-submitted ones it is not.
     */
    @Query("""
            select new dev.varun.forecast.api.repo.CorridorStats(
                s.corridorId, count(s), max(s.requestedAt))
            from Sample s
            group by s.corridorId
            """)
    List<CorridorStats> statsByCorridor();

    /**
     * The cache read. Anything newer than the cutoff is fresh enough to serve without
     * touching the routing API.
     */
    List<Sample> findByCorridorIdAndRequestedAtAfter(Long corridorId, Instant cutoff);

    List<Sample> findByCorridorId(Long corridorId);
}
