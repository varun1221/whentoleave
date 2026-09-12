package dev.varun.forecast.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One line of data/samples/&lt;routeId&gt;.jsonl. Append-only: these files are the
 * project's real asset and are never rewritten or truncated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Sample(
        String targetLocal,
        String dayOfWeek,
        int slotHour,
        long durationSeconds,
        Long distanceMeters,
        String requestedAt) {}
