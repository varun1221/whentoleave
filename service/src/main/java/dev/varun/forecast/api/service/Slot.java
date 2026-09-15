package dev.varun.forecast.api.service;

import java.time.DayOfWeek;

/** One cell of the grid. */
public record Slot(DayOfWeek day, int hour) {}
