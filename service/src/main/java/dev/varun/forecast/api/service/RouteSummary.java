package dev.varun.forecast.api.service;

import java.time.Instant;

/**
 * What {@code GET /api/routes} returns per corridor. A DTO rather than the entity, so
 * the database shape and the wire shape can move independently.
 */
public record RouteSummary(
        String id,
        String name,
        String origin,
        String dest,
        long sampleCount,
        Instant lastSampledAt) {}
