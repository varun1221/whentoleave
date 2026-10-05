package dev.varun.forecast.api.service;

/**
 * Hears a lookup's grid grow while its missing slots are fetched, so a visitor can be
 * shown the first hours in about a second instead of every hour after about eleven.
 *
 * <p>Only a lookup that fetches reports anything. A cache hit, a refusal or a degraded
 * answer is decided before the first call goes out, and is simply returned.
 */
@FunctionalInterface
public interface LookupProgress {

    LookupProgress NONE = soFar -> {};

    /**
     * Called once as fetching begins, with whatever was already cached, and again after
     * each slot is saved. Always on the thread that called
     * {@link LookupService#lookup}, never on one of the fill's own.
     */
    void filled(ForecastGrid soFar);
}
