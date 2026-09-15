package dev.varun.forecast.api.client;

import java.io.IOException;

/**
 * The request never left this process: no key is configured, or the connection failed
 * before anything was sent.
 *
 * <p>Distinct from any other failure because it decides what a request costs. §9.4 says
 * only actual TomTom calls count against the limits, and a call TomTom never received is
 * not one, so callers hand back whatever they reserved for it. A request TomTom answered
 * with an error was received and counted, and stays spent.
 */
public class CallNotSentException extends IOException {

    public CallNotSentException(String message) {
        super(message);
    }

    public CallNotSentException(String message, Throwable cause) {
        super(message, cause);
    }
}
