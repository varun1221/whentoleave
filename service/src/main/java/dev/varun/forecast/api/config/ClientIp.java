package dev.varun.forecast.api.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's address, for per-IP limiting.
 *
 * <p>Cloudflare sits in front of this service, so {@code getRemoteAddr()} is Cloudflare's
 * edge rather than the visitor — keying a limit on it would put every visitor in one
 * bucket. {@code CF-Connecting-IP} is the real client, and Cloudflare overwrites it on
 * the way through. That only holds for requests that actually came through Cloudflare:
 * a direct call to the Cloud Run hostname can set the header to anything, which is what
 * {@link OriginSecretFilter} is for.
 *
 * <p>Falling back to {@code getRemoteAddr()} matters for local development, where there
 * is no Cloudflare in the path at all.
 */
public final class ClientIp {

    private static final String CLOUDFLARE_HEADER = "CF-Connecting-IP";

    public static String of(HttpServletRequest request) {
        String header = request.getHeader(CLOUDFLARE_HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        return request.getRemoteAddr();
    }

    private ClientIp() {}
}
