package dev.varun.forecast.api.repo;

import dev.varun.forecast.api.domain.Corridor;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CorridorRepository extends JpaRepository<Corridor, Long> {

    /** The five Phase 1 corridors, in insertion order so the UI ordering is stable. */
    List<Corridor> findBySeededTrueOrderById();

    Optional<Corridor> findBySlug(String slug);

    /** Corridor identity for a user lookup is the coordinate pair, not a slug. */
    Optional<Corridor> findByOriginCoordAndDestCoord(String originCoord, String destCoord);
}
