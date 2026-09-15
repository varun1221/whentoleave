package dev.varun.forecast.api.service;

import dev.varun.forecast.api.config.ForecastProperties;
import dev.varun.forecast.api.repo.ServiceSettingRepository;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whether live lookups are permitted at all.
 *
 * <p>Separate from the daily quota: the quota is a budget that refills tomorrow, this is
 * a switch someone throws when something is wrong. When it is off, {@code /api/lookup}
 * serves whatever is already cached and says so, rather than failing.
 *
 * <p>Read through a short in-process cache so this does not become a database query on
 * every request, at the cost of taking that long to take effect.
 */
@Service
public class KillSwitch {

    private static final Logger log = LoggerFactory.getLogger(KillSwitch.class);
    private static final String KEY = "lookups_enabled";

    private final ServiceSettingRepository settings;
    private final Duration ttl;

    private volatile boolean cached = true;
    private volatile Instant readAt = Instant.EPOCH;

    public KillSwitch(ServiceSettingRepository settings, ForecastProperties props) {
        this.settings = settings;
        this.ttl = Duration.ofSeconds(props.killSwitch().cacheSeconds());
    }

    @Transactional(readOnly = true)
    public boolean lookupsEnabled() {
        Instant now = Instant.now();
        if (now.isBefore(readAt.plus(ttl))) {
            return cached;
        }
        // A missing row fails closed on the safe side for a cost control: no row means
        // nobody has said lookups are allowed.
        boolean enabled = settings.findById(KEY)
                .map(setting -> Boolean.parseBoolean(setting.getValue()))
                .orElse(false);
        if (enabled != cached) {
            log.warn("live lookups are now {}", enabled ? "ENABLED" : "PAUSED");
        }
        cached = enabled;
        readAt = now;
        return enabled;
    }
}
