package dev.varun.forecast.model;

import java.util.List;
import java.util.Map;

public record RouteForecast(
        String id,
        String name,
        Long distanceMeters,
        Map<String, List<Bucket>> buckets) {}
