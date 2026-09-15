package dev.varun.forecast.api.service;

import dev.varun.forecast.api.domain.Corridor;
import dev.varun.forecast.api.repo.CorridorRepository;
import dev.varun.forecast.api.repo.CorridorStats;
import dev.varun.forecast.api.repo.SampleRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side for the seeded corridors. */
@Service
public class ForecastService {

    private final CorridorRepository corridors;
    private final SampleRepository samples;

    public ForecastService(CorridorRepository corridors, SampleRepository samples) {
        this.corridors = corridors;
        this.samples = samples;
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
