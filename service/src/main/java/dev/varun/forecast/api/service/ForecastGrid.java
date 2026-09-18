package dev.varun.forecast.api.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The wire shape for a forecast, deliberately the same shape as the static
 * {@code forecasts.json} the Phase 1 site already renders, so the frontend reuses one
 * Heatmap component for both seeded corridors and user lookups.
 *
 * <p>Absent rather than null on the wire, as {@link dev.varun.forecast.api.web.ApiError}
 * is: the fields only some grids can fill — a notice and its reset time, the label and
 * slug a user's corridor never had — say nothing by being missing. {@code Bucket} keeps
 * its nulls, where a null median is a fact about that slot rather than a missing field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
         * Null when the grid is everything it should be. Otherwise the reason it is not,
         * which the UI renders as a banner. A degraded answer with an explanation beats
         * an error.
         */
        ApiCode notice,
        /**
         * When the limit behind {@code notice} lifts, for the limits that lift on their
         * own. Null when there is no notice, and null for the kill switch, which ends
         * when a human says so.
         *
         * <p>Here rather than only on {@code /api/quota} because the banner is one
         * sentence — "you have used your 5 lookups today, back at midnight" — and a UI
         * that had to fetch a second endpoint to finish it would either make every
         * degraded lookup two requests or show a time from a different moment than the
         * grid it sits above.
         */
        Instant resetsAt) {

    /**
     * @param resetsAt when the limit lifts, or null for one that has no scheduled end
     */
    public ForecastGrid withNotice(ApiCode reason, Instant resetsAt) {
        return new ForecastGrid(id, name, origin, dest, partial, distanceMeters,
                sampleCount, generatedAt, buckets, reason, resetsAt);
    }

    /** {@code medianSeconds} is null for a slot with no samples, never zero. */
    public record Bucket(int slotHour, Long medianSeconds, int n) {}
}
