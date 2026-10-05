package dev.varun.forecast.api.repo;

import dev.varun.forecast.api.domain.Corridor;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface CorridorRepository extends JpaRepository<Corridor, Long> {

    /** The five Phase 1 corridors, in insertion order so the UI ordering is stable. */
    List<Corridor> findBySeededTrueOrderById();

    Optional<Corridor> findBySlug(String slug);

    /** Corridor identity for a user lookup is the coordinate pair, not a slug. */
    Optional<Corridor> findByOriginCoordAndDestCoord(String originCoord, String destCoord);

    /**
     * Registers a user corridor unless the pair already has one. Two visitors can ask
     * about the same new pair at once; with a plain insert the second failed on the
     * unique constraint, and the error it logged carried both visitors' coordinates.
     *
     * <p>Transactional here, unlike the other repositories' writes, because its caller,
     * {@code LookupService.fill}, runs outside a transaction.
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO corridor (origin_coord, dest_coord) VALUES (:origin, :dest)
            ON CONFLICT (origin_coord, dest_coord) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("origin") String originCoord,
            @Param("dest") String destCoord);
}
