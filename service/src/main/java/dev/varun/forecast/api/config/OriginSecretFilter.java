package dev.varun.forecast.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses API requests that did not come through Cloudflare.
 *
 * <p>Per-IP limits key on {@code CF-Connecting-IP}, which Cloudflare sets and a client
 * cannot override — but only on requests that pass through Cloudflare. The Cloud Run
 * hostname is public too, and a caller hitting it directly can send any header it likes
 * and get a fresh budget per request. Restricting Cloud Run ingress to Cloudflare needs
 * a paid load balancer, which §2 rules out, so instead Cloudflare adds a secret header
 * on the way through and this checks for it.
 *
 * <p>Every path except the health probes, which Cloud Run sends to the container
 * directly. Deliberately an allow-list of exact raw paths rather than a check for
 * {@code /api/}: Spring matches controllers on the decoded path with {@code ;}
 * parameters stripped, so {@code /%61pi/lookup} and {@code /api;x/lookup} both reach
 * the API while failing a prefix check on the raw URI.
 */
@Component
public class OriginSecretFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Origin-Secret";

    private static final Set<String> PROBES = Set.of(
            "/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness");

    private static final Logger log = LoggerFactory.getLogger(OriginSecretFilter.class);

    private final ForecastProperties.Edge edge;

    public OriginSecretFilter(ForecastProperties props) {
        this.edge = props.edge();
        if (!edge.enforced()) {
            log.warn("forecast.edge.origin-secret is not set: CF-Connecting-IP is trusted "
                    + "from any caller. Fine locally; never in deployment.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !edge.enforced() || PROBES.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (matches(request.getHeader(HEADER))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"forbidden\",\"message\":\"Requests must come through the site\"}");
    }

    /** Constant-time, so the secret cannot be recovered a byte at a time from timings. */
    private boolean matches(String presented) {
        if (presented == null) {
            return false;
        }
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                edge.originSecret().getBytes(StandardCharsets.UTF_8));
    }
}
