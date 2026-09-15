package dev.varun.forecast.api.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The wire shape for a forecast, deliberately the same shape as the static
 * {@code forecasts.json} the Phase 1 site already renders, so the frontend reuses one
 * Heatmap component for both seeded corridors and user lookups.
 */
public record ForecastGrid(
        String id,
        String name,
        String origin,
        String dest,
        /** A reduced grid — weekday peaks only. The UI labels it as a partial profile. */
        boolean partial,
        Integer distanceMeters,
        int sampleCount,
        Instant generatedAt,
        /** Day name to buckets, e.g. "MONDAY". */
        Map<String, List<Bucket>> buckets,
        /**
         * Null when the grid is everything it should be. Otherwise a reason the UI
         * renders as a banner: {@code lookups_paused}, {@code quota_exhausted},
         * {@code rate_limited}. A degraded answer with an explanation beats an error.
         */
        String notice) {

    public ForecastGrid withNotice(String reason) {
        return new ForecastGrid(id, name, origin, dest, partial, distanceMeters,
                sampleCount, generatedAt, buckets, reason);
    }

    /** {@code medianSeconds} is null for a slot with no samples, never zero. */
    public record Bucket(int slotHour, Long medianSeconds, int n) {}
}
