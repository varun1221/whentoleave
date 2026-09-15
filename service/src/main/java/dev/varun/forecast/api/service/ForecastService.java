package dev.varun.forecast.api.service;

import dev.varun.forecast.api.domain.Corridor;
import dev.varun.forecast.api.domain.Sample;
import dev.varun.forecast.api.repo.CorridorRepository;
import dev.varun.forecast.api.repo.CorridorStats;
import dev.varun.forecast.api.repo.SampleRepository;
import java.time.DayOfWeek;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side for the seeded corridors. */
@Service
public class ForecastService {

    /** The seeded corridors keep the full grid the Phase 1 sampler fills. */
    private static final List<DayOfWeek> ALL_DAYS = List.of(DayOfWeek.values());
    private static final List<Integer> ALL_HOURS =
            IntStream.rangeClosed(6, 18).boxed().toList();

    private final CorridorRepository corridors;
    private final SampleRepository samples;
    private final GridBuilder grids;

    public ForecastService(CorridorRepository corridors, SampleRepository samples,
            GridBuilder grids) {
        this.corridors = corridors;
        this.samples = samples;
        this.grids = grids;
    }

    /**
     * The whole grid for one seeded corridor, over its entire history rather than a
     * freshness window — this is the committed dataset, not a cache read.
     */
    @Transactional(readOnly = true)
    public Optional<ForecastGrid> forecastFor(String slug) {
        return corridors.findBySlug(slug).map(corridor -> {
            List<Sample> all = samples.findByCorridorId(corridor.getId());
            return grids.build(corridor, all, ALL_DAYS, ALL_HOURS, false);
        });
    }

    /**
     * The seeded corridors with their sample totals.
     *
     * <p>Two queries regardless of corridor count: the corridors, then one grouped
     * aggregate over samples, merged here.
     */
    @Transactional(readOnly = true)
    public List<RouteSummary> listSeededRoutes() {
        Map<Long, CorridorStats> stats = new HashMap<>();
        for (CorridorStats row : samples.statsByCorridor()) {
            stats.put(row.corridorId(), row);
        }

        List<RouteSummary> out = new java.util.ArrayList<>();
        for (Corridor corridor : corridors.findBySeededTrueOrderById()) {
            CorridorStats row = stats.get(corridor.getId());
            out.add(new RouteSummary(
                    corridor.getSlug(),
                    corridor.getLabel(),
                    corridor.getOriginCoord(),
                    corridor.getDestCoord(),
                    row == null ? 0L : row.total(),
                    row == null ? null : row.latest()));
        }
        return out;
    }
}
