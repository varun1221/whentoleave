package dev.varun.forecast.model;

import java.util.List;

/** The whole of web/public/data/forecasts.json. */
public record ForecastsFile(
        String generatedAt,
        int weeksSampled,
        int totalSamples,
        List<RouteForecast> routes) {}
