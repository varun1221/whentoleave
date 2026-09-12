package dev.varun.forecast.model;

/** A single computeRoutes response, already parsed. */
public record RouteResult(long durationSeconds, Long distanceMeters) {}
