package dev.varun.forecast.api.web;

import dev.varun.forecast.api.config.ClientIp;
import dev.varun.forecast.api.service.DailyIpLimiter;
import dev.varun.forecast.api.service.KillSwitch;
import dev.varun.forecast.api.service.QuotaDay;
import dev.varun.forecast.api.service.QuotaService;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class QuotaController {

    private final QuotaService quotas;
    private final DailyIpLimiter perIp;
    private final KillSwitch killSwitch;
    private final QuotaDay day;

    public QuotaController(QuotaService quotas, DailyIpLimiter perIp,
            KillSwitch killSwitch, QuotaDay day) {
        this.quotas = quotas;
        this.perIp = perIp;
        this.killSwitch = killSwitch;
        this.day = day;
    }

    /**
     * What this visitor has left, and what the service has left. Shown in the UI rather
     * than discovered by hitting a wall — visible limits read as intentional design.
     */
    @GetMapping("/quota")
    public QuotaStatus quota(HttpServletRequest http) {
        String ip = ClientIp.of(http);
        int globalRemaining = quotas.remaining();
        int lookupLimit = perIp.dailyLimit(DailyIpLimiter.Budget.LOOKUP);
        int searchLimit = perIp.dailyLimit(DailyIpLimiter.Budget.SEARCH);
        long lookupsRemaining = perIp.remaining(DailyIpLimiter.Budget.LOOKUP, ip);
        long searchesRemaining = perIp.remaining(DailyIpLimiter.Budget.SEARCH, ip);
        boolean paused = !killSwitch.lookupsEnabled();
        return new QuotaStatus(
                lookupsRemaining,
                lookupLimit,
                searchesRemaining,
                searchLimit,
                globalRemaining,
                quotas.dailyCeiling(),
                paused,
                !paused && globalRemaining > 0 && lookupsRemaining > 0,
                day.resetsAt());
    }

    public record QuotaStatus(
            long yourRemaining,
            int yourDailyLimit,
            long yourSearchesRemaining,
            int yourSearchDailyLimit,
            int globalRemaining,
            int globalDailyCeiling,
            boolean lookupsPaused,
            boolean lookupsAvailable,
            /** Pacific midnight, when both the per-IP and the global budgets refill. */
            Instant resetsAt) {}
}
