package dev.varun.forecast.api.client;

/**
 * Permission to send requests to TomTom, one request at a time.
 *
 * <p>Passed <em>into</em> a call rather than checked around it, because one call is not
 * one request: {@link RoutingClient#compute} retries, and §9.4 counts every request that
 * reaches TomTom. A retry therefore has to ask like any other request, and a retry that
 * cannot be afforded is not sent — §7 makes the daily ceiling the hard stop, so the slot
 * fails rather than the ceiling bending.
 */
public interface CallBudget {

    /** @return whether one more request may go out */
    boolean tryAcquire();

    /**
     * Hands back a request that was acquired but never left the process, so it costs
     * nothing. See {@link CallNotSentException}.
     */
    void refund();
}
