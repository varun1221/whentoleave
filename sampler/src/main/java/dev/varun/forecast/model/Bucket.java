package dev.varun.forecast.model;

/**
 * One cell of the heatmap. {@code medianSeconds} is null when no sample exists —
 * never 0, which the UI would render as an instant trip.
 */
public record Bucket(int slotHour, Long medianSeconds, int n) {}
