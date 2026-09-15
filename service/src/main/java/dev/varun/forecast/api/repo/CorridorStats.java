package dev.varun.forecast.api.repo;

import java.time.Instant;

/**
 * Per-corridor sample totals, aggregated in one query so listing routes does not fan out
 * into a count per corridor.
 */
public record CorridorStats(Long corridorId, Long total, Instant latest) {}
